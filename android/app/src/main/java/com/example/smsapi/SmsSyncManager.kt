package com.example.smsapi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object SmsSyncManager {

    fun syncInboxToBackend(context: Context, backendBaseUrl: String = BackendConfig.getBackendBaseUrl(context)): SyncResult {
        val syncUrl = BackendConfig.buildMessagesSyncUrl(backendBaseUrl)
        if (syncUrl.isBlank()) {
            return SyncResult(success = false, syncedCount = 0, detail = "No backend URL configured")
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return SyncResult(success = false, syncedCount = 0, detail = "READ_SMS permission is required to sync")
        }

        val inboxMessages = loadInboxMessages(context)
        return pushInboxState(syncUrl, inboxMessages)
    }

    private fun loadInboxMessages(context: Context): List<PhoneMessage> {
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
        )

        return buildList {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                val addressColumn = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyColumn = cursor.getColumnIndex(Telephony.Sms.BODY)
                val dateColumn = cursor.getColumnIndex(Telephony.Sms.DATE)

                while (cursor.moveToNext()) {
                    val address = cursor.getString(addressColumn)?.trim().takeUnless { it.isNullOrEmpty() } ?: "Unknown"
                    val body = cursor.getString(bodyColumn)?.trim().orEmpty()
                    if (body.isBlank()) {
                        continue
                    }

                    val receivedAt = cursor.getLong(dateColumn)
                    add(PhoneMessage(address = address, body = body, receivedAt = receivedAt))
                }
            }
        }
    }

    private fun pushInboxState(syncUrl: String, messages: List<PhoneMessage>): SyncResult {
        return try {
            val payload = JSONObject()
                .put(
                    "messages",
                    JSONArray().apply {
                        messages.forEach { message ->
                            put(
                                JSONObject()
                                    .put("address", message.address)
                                    .put("body", message.body)
                                    .put("receivedAt", message.receivedAt),
                            )
                        }
                    },
                )
                .toString()

            val connection = (URL(syncUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }

            try {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload)
                }

                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    SyncResult(
                        success = true,
                        syncedCount = messages.size,
                        detail = "Synced ${messages.size} phone messages to the backend",
                    )
                } else {
                    SyncResult(
                        success = false,
                        syncedCount = messages.size,
                        detail = "Backend sync failed with HTTP $responseCode ${connection.responseMessage}",
                    )
                }
            } finally {
                connection.disconnect()
            }
        } catch (error: Exception) {
            SyncResult(
                success = false,
                syncedCount = messages.size,
                detail = error.message ?: error::class.java.simpleName,
            )
        }
    }

    data class SyncResult(
        val success: Boolean,
        val syncedCount: Int,
        val detail: String,
    )

    private data class PhoneMessage(
        val address: String,
        val body: String,
        val receivedAt: Long,
    )
}
