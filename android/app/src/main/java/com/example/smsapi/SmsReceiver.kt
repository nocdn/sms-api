package com.example.smsapi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
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
        val pendingResult = goAsync()

        AppLogStore.append(
            context,
            TAG,
            "Received SMS broadcast from $address at $receivedAt: ${body.take(160)}",
        )

        executor.execute {
            try {
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
                    Log.w(TAG, "SMS sync failed: ${syncResult.detail}")
                } else {
                    Log.d(TAG, "SMS sync completed with ${syncResult.syncedCount} messages")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
        private val executor = Executors.newSingleThreadExecutor()
    }
}
