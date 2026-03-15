package com.example.smsapi

import java.security.MessageDigest
import kotlin.math.min

object SmsQueueState {

    const val MAX_QUEUE_SIZE = 500
    const val PROCESSED_TTL_MS = 7L * 24 * 60 * 60 * 1000
    const val BACKOFF_BASE_MS = 30_000L
    const val BACKOFF_MAX_MS = 6L * 60 * 60 * 1000

    data class QueueStateSnapshot(
        val queued: List<QueuedMessage>,
        val processed: List<ProcessedMessage>,
    )

    data class QueuedMessage(
        val key: String,
        val address: String,
        val body: String,
        val receivedAt: Long,
        val enqueuedAt: Long,
        val attemptCount: Int,
        val nextEligibleAt: Long,
    )

    data class ProcessedMessage(
        val key: String,
        val processedAt: Long,
    )

    data class EnqueueResult(
        val state: QueueStateSnapshot,
        val enqueued: Boolean,
        val reason: String,
        val message: QueuedMessage? = null,
    )

    fun normalizeAddress(address: String): String {
        return address.trim().lowercase()
    }

    fun normalizeBody(body: String): String {
        return body.trim().replace(Regex("\\s+"), " ")
    }

    fun buildMessageKey(address: String, body: String, receivedAt: Long): String {
        val normalizedAddress = normalizeAddress(address)
        val normalizedBody = normalizeBody(body)
        val raw = "$normalizedAddress|$normalizedBody|$receivedAt"
        return sha256(raw)
    }

    fun enqueueMessage(
        state: QueueStateSnapshot,
        address: String,
        body: String,
        receivedAt: Long,
        now: Long,
    ): EnqueueResult {
        val normalizedAddress = address.trim()
        val normalizedBody = body.trim()
        if (normalizedBody.isBlank()) {
            return EnqueueResult(state = state, enqueued = false, reason = "empty_body")
        }

        val messageKey = buildMessageKey(normalizedAddress, normalizedBody, receivedAt)
        val prunedProcessed = pruneProcessed(state.processed, now)

        if (state.queued.any { it.key == messageKey }) {
            return EnqueueResult(
                state = state.copy(processed = prunedProcessed),
                enqueued = false,
                reason = "duplicate_in_queue",
            )
        }

        if (prunedProcessed.any { it.key == messageKey }) {
            return EnqueueResult(
                state = state.copy(processed = prunedProcessed),
                enqueued = false,
                reason = "duplicate_recently_processed",
            )
        }

        val newMessage = QueuedMessage(
            key = messageKey,
            address = normalizedAddress,
            body = normalizedBody,
            receivedAt = receivedAt,
            enqueuedAt = now,
            attemptCount = 0,
            nextEligibleAt = now,
        )

        val trimmedQueue = trimQueue(state.queued + newMessage)
        return EnqueueResult(
            state = QueueStateSnapshot(queued = trimmedQueue, processed = prunedProcessed),
            enqueued = true,
            reason = "enqueued",
            message = newMessage,
        )
    }

    fun markProcessed(
        state: QueueStateSnapshot,
        processedKeys: Set<String>,
        now: Long,
    ): QueueStateSnapshot {
        if (processedKeys.isEmpty()) {
            return state
        }

        val remainingQueue = state.queued.filterNot { it.key in processedKeys }
        val combinedProcessed = state.processed + processedKeys.map { key ->
            ProcessedMessage(key = key, processedAt = now)
        }

        return QueueStateSnapshot(
            queued = remainingQueue,
            processed = dedupeProcessed(pruneProcessed(combinedProcessed, now)),
        )
    }

    fun markAttempted(
        state: QueueStateSnapshot,
        attemptedKeys: Set<String>,
        now: Long,
    ): QueueStateSnapshot {
        if (attemptedKeys.isEmpty()) {
            return state
        }

        val updatedQueue = state.queued.map { message ->
            if (message.key in attemptedKeys) {
                val newAttempt = message.attemptCount + 1
                val delay = calculateBackoffDelay(newAttempt)
                message.copy(
                    attemptCount = newAttempt,
                    nextEligibleAt = now + delay,
                )
            } else {
                message
            }
        }

        return state.copy(queued = updatedQueue)
    }

    fun calculateBackoffDelay(attemptCount: Int): Long {
        if (attemptCount <= 0) {
            return 0L
        }

        var delay = BACKOFF_BASE_MS
        repeat(attemptCount - 1) {
            delay = min(delay * 2, BACKOFF_MAX_MS)
        }

        return delay
    }

    fun pruneProcessed(processed: List<ProcessedMessage>, now: Long): List<ProcessedMessage> {
        val threshold = now - PROCESSED_TTL_MS
        return processed.filter { it.processedAt >= threshold }
    }

    private fun trimQueue(queue: List<QueuedMessage>): List<QueuedMessage> {
        if (queue.size <= MAX_QUEUE_SIZE) {
            return queue
        }

        return queue.sortedBy { it.enqueuedAt }.takeLast(MAX_QUEUE_SIZE)
    }

    private fun dedupeProcessed(processed: List<ProcessedMessage>): List<ProcessedMessage> {
        return processed
            .groupBy { it.key }
            .mapNotNull { (_, items) -> items.maxByOrNull { it.processedAt } }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
