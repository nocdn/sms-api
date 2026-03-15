package com.example.smsapi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object SmsQueueStore {

    private const val PREFS_NAME = "SmsApiQueue"
    private const val QUEUE_KEY = "queue"
    private const val PROCESSED_KEY = "processed"

    @Synchronized
    fun enqueueMessage(
        context: Context,
        address: String,
        body: String,
        receivedAt: Long,
        now: Long = System.currentTimeMillis(),
    ): SmsQueueState.EnqueueResult {
        val state = readState(context)
        val result = SmsQueueState.enqueueMessage(state, address, body, receivedAt, now)
        writeState(context, result.state)
        return result
    }

    @Synchronized
    fun readState(context: Context): SmsQueueState.QueueStateSnapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawQueue = prefs.getString(QUEUE_KEY, null)
        val rawProcessed = prefs.getString(PROCESSED_KEY, null)

        val queueItems = parseQueue(rawQueue)
        val processedItems = SmsQueueState.pruneProcessed(parseProcessed(rawProcessed), System.currentTimeMillis())

        val snapshot = SmsQueueState.QueueStateSnapshot(
            queued = queueItems,
            processed = processedItems,
        )

        if (rawProcessed != null) {
            writeState(context, snapshot)
        }

        return snapshot
    }

    @Synchronized
    fun markProcessed(
        context: Context,
        processedKeys: Set<String>,
        now: Long = System.currentTimeMillis(),
    ) {
        if (processedKeys.isEmpty()) {
            return
        }

        val state = readState(context)
        val updated = SmsQueueState.markProcessed(state, processedKeys, now)
        writeState(context, updated)
    }

    @Synchronized
    fun markAttempted(
        context: Context,
        attemptedKeys: Set<String>,
        now: Long = System.currentTimeMillis(),
    ) {
        if (attemptedKeys.isEmpty()) {
            return
        }

        val state = readState(context)
        val updated = SmsQueueState.markAttempted(state, attemptedKeys, now)
        writeState(context, updated)
    }

    @Synchronized
    fun overwriteState(context: Context, state: SmsQueueState.QueueStateSnapshot) {
        writeState(context, state)
    }

    private fun writeState(context: Context, state: SmsQueueState.QueueStateSnapshot) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(QUEUE_KEY, serializeQueue(state.queued))
            .putString(PROCESSED_KEY, serializeProcessed(state.processed))
            .apply()
    }

    private fun parseQueue(rawValue: String?): List<SmsQueueState.QueuedMessage> {
        if (rawValue.isNullOrBlank()) {
            return emptyList()
        }

        return try {
            val jsonArray = JSONArray(rawValue)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(index) ?: continue
                    add(
                        SmsQueueState.QueuedMessage(
                            key = item.optString("key"),
                            address = item.optString("address"),
                            body = item.optString("body"),
                            receivedAt = item.optLong("receivedAt"),
                            enqueuedAt = item.optLong("enqueuedAt"),
                            attemptCount = item.optInt("attemptCount"),
                            nextEligibleAt = item.optLong("nextEligibleAt"),
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseProcessed(rawValue: String?): List<SmsQueueState.ProcessedMessage> {
        if (rawValue.isNullOrBlank()) {
            return emptyList()
        }

        return try {
            val jsonArray = JSONArray(rawValue)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(index) ?: continue
                    add(
                        SmsQueueState.ProcessedMessage(
                            key = item.optString("key"),
                            processedAt = item.optLong("processedAt"),
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serializeQueue(queue: List<SmsQueueState.QueuedMessage>): String {
        val jsonArray = JSONArray()
        queue.forEach { message ->
            jsonArray.put(
                JSONObject()
                    .put("key", message.key)
                    .put("address", message.address)
                    .put("body", message.body)
                    .put("receivedAt", message.receivedAt)
                    .put("enqueuedAt", message.enqueuedAt)
                    .put("attemptCount", message.attemptCount)
                    .put("nextEligibleAt", message.nextEligibleAt)
            )
        }
        return jsonArray.toString()
    }

    private fun serializeProcessed(processed: List<SmsQueueState.ProcessedMessage>): String {
        val jsonArray = JSONArray()
        processed.forEach { message ->
            jsonArray.put(
                JSONObject()
                    .put("key", message.key)
                    .put("processedAt", message.processedAt)
            )
        }
        return jsonArray.toString()
    }
}
