package com.example.smsapi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val backendBaseUrl = BackendConfig.getBackendBaseUrl(context)
        if (backendBaseUrl.isBlank()) {
            Log.w(TAG, "No backend URL configured, queueing SMS for later.")
        }

        val smsParts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (smsParts.isEmpty()) {
            Log.w(TAG, "SMS broadcast did not contain any message parts.")
            return
        }

        val address = smsParts.firstOrNull()?.displayOriginatingAddress?.takeUnless { it.isBlank() } ?: "Unknown"
        val body = smsParts.joinToString(separator = "") { it.displayMessageBody.orEmpty() }
        val receivedAt = smsParts.maxOfOrNull { it.timestampMillis } ?: System.currentTimeMillis()

        val enqueueResult = SmsQueueStore.enqueueMessage(
            context = context,
            address = address,
            body = body,
            receivedAt = receivedAt,
        )

        AppLogStore.append(
            context,
            TAG,
            "Queued SMS from $address at $receivedAt (${enqueueResult.reason})",
        )

        if (backendBaseUrl.isNotBlank()) {
            SmsWorkScheduler.scheduleOneTimeSync(context, "sms-received")
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
