package com.example.smsapi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val backendBaseUrl = BackendConfig.getBackendBaseUrl(context)
        if (backendBaseUrl.isBlank()) {
            Log.w(TAG, "No backend URL configured, skipping SMS forward.")
            return
        }

        val smsParts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (smsParts.isEmpty()) {
            Log.w(TAG, "SMS broadcast did not contain any message parts.")
            return
        }

        val address = smsParts.firstOrNull()?.displayOriginatingAddress?.takeUnless { it.isBlank() } ?: "Unknown"
        val body = smsParts.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
        val receivedAt = smsParts.maxOfOrNull { it.timestampMillis } ?: System.currentTimeMillis()
        val messagesUrl = BackendConfig.buildMessagesUrl(backendBaseUrl)
        val pendingResult = goAsync()

        AppLogStore.append(
            context,
            TAG,
            "Received SMS broadcast from $address at $receivedAt: ${body.take(160)}",
        )

        executor.execute {
            try {
                val wasStored = postToBackend(context.applicationContext, messagesUrl, address, body, receivedAt)
                if (wasStored) {
                    Log.d(TAG, "SMS forwarded to backend, starting source-of-truth sync")
                    val syncResult = SmsSyncManager.syncInboxToBackend(
                        context = context.applicationContext,
                        backendBaseUrl = backendBaseUrl,
                        extraMessages = listOf(
                            SmsSyncManager.PhoneMessage(
                                address = address,
                                body = body,
                                receivedAt = receivedAt,
                            ),
                        ),
                    )
                    if (!syncResult.success) {
                        Log.w(TAG, "SMS stored but backend sync failed: ${syncResult.detail}")
                    } else {
                        Log.d(TAG, "SMS sync completed with ${syncResult.syncedCount} messages")
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun postToBackend(context: Context, messagesUrl: String, address: String, body: String, receivedAt: Long): Boolean {
        return try {
            val payload = JSONObject()
                .put("address", address)
                .put("body", body)
                .put("receivedAt", receivedAt)
                .toString()

            AppLogStore.append(
                context,
                TAG,
                "OUT POST /api/messages -> $messagesUrl for $address at $receivedAt",
            )

            val connection = (URL(messagesUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }

            try {
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload)
                }

                val responseCode = connection.responseCode
                Log.d(TAG, "Backend responded with code: $responseCode")
                AppLogStore.append(
                    context,
                    TAG,
                    "IN  POST /api/messages <- HTTP $responseCode ${connection.responseMessage}",
                )
                responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to forward SMS: ${error.message}", error)
            AppLogStore.append(
                context,
                TAG,
                "ERR POST /api/messages <- ${error.message ?: error::class.java.simpleName}",
            )
            false
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
        private val executor = Executors.newSingleThreadExecutor()
    }
}
