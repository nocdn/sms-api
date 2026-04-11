package com.example.smsapi

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ApiContract {

    const val SERVER_PORT = 6770
    const val HEALTH_PATH = "/health"
    const val MESSAGES_PATH = "/api/messages"

    fun parseLastQuery(value: String?): Int? {
        if (value.isNullOrBlank()) {
            return 1
        }

        val parsed = value.toIntOrNull() ?: return null
        return parsed.takeIf { it == -1 || it > 0 }
    }

    fun formatTimestamp(timestampMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestampMillis))
    }
}
