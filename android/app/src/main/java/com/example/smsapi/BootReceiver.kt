package com.example.smsapi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            -> {
                AppLogStore.append(context, "BootReceiver", "Starting foreground server after system event ${intent.action}")
                ServerForegroundService.start(context)
            }
        }
    }
}
