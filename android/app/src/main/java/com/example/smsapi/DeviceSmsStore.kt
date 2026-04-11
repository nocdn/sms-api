package com.example.smsapi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.BaseColumns
import android.provider.Telephony
import androidx.core.content.ContextCompat

object DeviceSmsStore {

    fun hasReadPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }

    fun listMessages(context: Context, last: Int): List<DeviceMessage> {
        if (!hasReadPermission(context)) {
            return emptyList()
        }

        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
        )

        return buildList {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC, ${BaseColumns._ID} DESC",
            )?.use { cursor ->
                val addressColumn = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyColumn = cursor.getColumnIndex(Telephony.Sms.BODY)
                val dateColumn = cursor.getColumnIndex(Telephony.Sms.DATE)

                while (cursor.moveToNext()) {
                    add(
                        DeviceMessage(
                            address = cursor.getString(addressColumn)?.trim().takeUnless { it.isNullOrEmpty() } ?: "Unknown",
                            body = cursor.getString(bodyColumn)?.trim().orEmpty(),
                            receivedAt = cursor.getLong(dateColumn),
                        ),
                    )

                    if (last != -1 && size >= last) {
                        break
                    }
                }
            }
        }
    }

    data class DeviceMessage(
        val address: String,
        val body: String,
        val receivedAt: Long,
    )
}
