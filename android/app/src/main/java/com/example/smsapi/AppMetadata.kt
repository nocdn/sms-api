package com.example.smsapi

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object AppMetadata {

    fun versionName(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }

            packageInfo.versionName.orEmpty().ifBlank { "Unknown" }
        } catch (_: Exception) {
            "Unknown"
        }
    }
}
