package com.example.smsapi

import android.content.Context
import java.net.URL

object BackendConfig {

    private const val PREFS_NAME = "SmsApiPrefs"
    private const val BACKEND_BASE_URL_KEY = "backend_base_url"

    fun getBackendBaseUrl(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(BACKEND_BASE_URL_KEY, "")
            .orEmpty()
    }

    fun saveBackendBaseUrl(context: Context, value: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(BACKEND_BASE_URL_KEY, value)
            .apply()
    }

    fun normalizeBackendBaseUrl(value: String): String {
        var normalized = value.trim()

        if (normalized.endsWith("/api/messages")) {
            normalized = normalized.removeSuffix("/api/messages")
        }

        return normalized.trimEnd('/')
    }

    fun isValidBackendBaseUrl(value: String): Boolean {
        if (value.isBlank()) {
            return true
        }

        return try {
            val parsedUrl = URL(value)
            (parsedUrl.protocol == "http" || parsedUrl.protocol == "https") && parsedUrl.host.isNotBlank()
        } catch (_: Exception) {
            false
        }
    }

    fun buildMessagesUrl(baseUrl: String): String {
        val normalized = normalizeBackendBaseUrl(baseUrl)
        if (normalized.isBlank()) {
            return ""
        }

        return "$normalized/api/messages"
    }

    fun buildHealthUrl(baseUrl: String): String {
        val normalized = normalizeBackendBaseUrl(baseUrl)
        if (normalized.isBlank()) {
            return ""
        }

        return "$normalized/health"
    }
}
