package com.example.smsapi

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogStore {

    private const val PREFS_NAME = "SmsApiLogs"
    private const val LOGS_KEY = "entries"
    private const val MAX_ENTRIES = 250
    private val timestampFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun append(context: Context, source: String, message: String) {
        writeToLogcat(source, message)

        val entries = readEntries(context.applicationContext).toMutableList()
        entries.add("[${timestampFormatter.format(Date())}] [$source] $message")

        val trimmedEntries = entries.takeLast(MAX_ENTRIES)
        val jsonArray = JSONArray()
        trimmedEntries.forEach { entry -> jsonArray.put(entry) }

        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(LOGS_KEY, jsonArray.toString())
            .apply()
    }

    @Synchronized
    fun getText(context: Context): String {
        val entries = readEntries(context.applicationContext)
        return if (entries.isEmpty()) {
            "No logs yet"
        } else {
            entries.joinToString(separator = "\n")
        }
    }

    @Synchronized
    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(LOGS_KEY)
            .apply()
    }

    private fun readEntries(context: Context): List<String> {
        val rawValue = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(LOGS_KEY, null)
            ?: return emptyList()

        return try {
            val jsonArray = JSONArray(rawValue)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    add(jsonArray.optString(index))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeToLogcat(source: String, message: String) {
        val tag = "SmsApi/$source"
        when {
            message.startsWith("ERR ") || message.contains("Failed", ignoreCase = true) -> Log.e(tag, message)
            message.startsWith("WARN ") -> Log.w(tag, message)
            else -> Log.i(tag, message)
        }
    }
}
