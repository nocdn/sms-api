package com.example.smsapi

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ApiContract {

    const val SERVER_PORT = 6770
    const val HEALTH_PATH = "/health"
    const val MESSAGES_PATH = "/api/messages"
    private val emptyJsonObjectPattern = Regex("""\{\s*\}""")
    private val lastBodyPattern = Regex("""\{\s*"last"\s*:\s*(?:"([^"]+)"|(-?\d+))\s*\}""")

    fun parseMessagesRequest(rawBody: String?): MessagesRequest? {
        val normalizedBody = rawBody?.trim()
        if (normalizedBody.isNullOrBlank()) {
            return MessagesRequest(last = 1)
        }

        if (emptyJsonObjectPattern.matches(normalizedBody)) {
            return MessagesRequest(last = 1)
        }

        val bodyMatch = lastBodyPattern.matchEntire(normalizedBody) ?: return null
        val rawLastValue = bodyMatch.groups[1]?.value ?: bodyMatch.groups[2]?.value ?: return null
        val parsedLast = parseLastValue(rawLastValue) ?: return null
        return MessagesRequest(last = parsedLast)
    }

    fun formatTimestamp(timestampMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestampMillis))
    }

    private fun parseLastValue(value: String): Int? {
        val parsed = value.trim().toIntOrNull() ?: return null

        return parsed.takeIf { it == -1 || it > 0 }
    }

    data class MessagesRequest(
        val last: Int,
    )
}
