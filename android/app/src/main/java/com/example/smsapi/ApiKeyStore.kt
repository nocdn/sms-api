package com.example.smsapi

import android.content.Context
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

object ApiKeyStore {

    private const val PREFS_NAME = "api_keys"
    private const val PREFS_KEY_ITEMS = "items"
    private val random = SecureRandom()

    @Synchronized
    fun listKeys(context: Context): List<ApiKey> {
        val rawItems = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREFS_KEY_ITEMS, null)
            .orEmpty()

        if (rawItems.isBlank()) {
            return emptyList()
        }

        return try {
            val parsedItems = JSONArray(rawItems)
            buildList {
                for (index in 0 until parsedItems.length()) {
                    val item = parsedItems.optJSONObject(index) ?: continue
                    val name = item.optString("name").trim().takeUnless { it.isEmpty() } ?: continue
                    val value = item.optString("value").trim().takeUnless { it.isEmpty() } ?: continue
                    val createdAt = item.optLong("createdAt")
                    add(ApiKey(name = name, value = value, createdAt = createdAt))
                }
            }.sortedByDescending(ApiKey::createdAt)
        } catch (error: Exception) {
            AppLogStore.append(
                context,
                "ApiKey",
                "ERR Failed to parse saved API keys: ${error.message ?: error::class.java.simpleName}",
            )
            emptyList()
        }
    }

    @Synchronized
    fun createKey(context: Context, name: String): ApiKey {
        val normalizedName = name.trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("API key name is required")
        val existingKeys = listKeys(context)
        val generatedValue = generateUniqueValue(existingKeys.map(ApiKey::value).toSet())
        val apiKey = ApiKey(
            name = normalizedName,
            value = generatedValue,
            createdAt = System.currentTimeMillis(),
        )

        persistKeys(context, listOf(apiKey) + existingKeys)
        AppLogStore.append(context, "ApiKey", "Created API key '${apiKey.name}'")
        return apiKey
    }

    @Synchronized
    fun revokeKey(context: Context, value: String): Boolean {
        val existingKeys = listKeys(context)
        val remainingKeys = existingKeys.filterNot { it.value == value }
        if (remainingKeys.size == existingKeys.size) {
            return false
        }

        val revokedKey = existingKeys.firstOrNull { it.value == value }
        persistKeys(context, remainingKeys)
        AppLogStore.append(context, "ApiKey", "Revoked API key '${revokedKey?.name ?: value}'")
        return true
    }

    @Synchronized
    fun containsValue(context: Context, value: String): Boolean {
        return listKeys(context).any { it.value == value }
    }

    private fun persistKeys(context: Context, keys: List<ApiKey>) {
        val payload = JSONArray().apply {
            keys.forEach { apiKey ->
                put(
                    JSONObject()
                        .put("name", apiKey.name)
                        .put("value", apiKey.value)
                        .put("createdAt", apiKey.createdAt),
                )
            }
        }

        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFS_KEY_ITEMS, payload.toString())
            .commit()
    }

    private fun generateUniqueValue(existingValues: Set<String>): String {
        while (true) {
            val candidate = buildString {
                append("sms_")
                repeat(10) {
                    append(random.nextInt(10))
                }
            }

            if (candidate !in existingValues) {
                return candidate
            }
        }
    }

    data class ApiKey(
        val name: String,
        val value: String,
        val createdAt: Long,
    )
}
