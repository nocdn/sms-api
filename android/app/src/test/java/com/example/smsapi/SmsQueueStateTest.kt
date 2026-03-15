package com.example.smsapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsQueueStateTest {

    @Test
    fun buildMessageKey_isStableForNormalizedBodyAndAddress() {
        val keyOne = SmsQueueState.buildMessageKey(" +447419740587 ", "Hello   world", 1234L)
        val keyTwo = SmsQueueState.buildMessageKey("+447419740587", "Hello world", 1234L)

        assertEquals(keyOne, keyTwo)
    }

    @Test
    fun buildMessageKey_changesWhenReceivedAtChanges() {
        val keyOne = SmsQueueState.buildMessageKey("+447419740587", "Hello", 1234L)
        val keyTwo = SmsQueueState.buildMessageKey("+447419740587", "Hello", 5678L)

        assertNotEquals(keyOne, keyTwo)
    }

    @Test
    fun enqueueMessage_skipsDuplicatesInQueueAndProcessed() {
        val now = 1_700_000_000_000L
        val messageKey = SmsQueueState.buildMessageKey("+1555", "Hi", 1000L)
        val state = SmsQueueState.QueueStateSnapshot(
            queued = listOf(
                SmsQueueState.QueuedMessage(
                    key = messageKey,
                    address = "+1555",
                    body = "Hi",
                    receivedAt = 1000L,
                    enqueuedAt = now,
                    attemptCount = 0,
                    nextEligibleAt = now,
                )
            ),
            processed = listOf(
                SmsQueueState.ProcessedMessage(
                    key = "processed-key",
                    processedAt = now,
                )
            )
        )

        val result = SmsQueueState.enqueueMessage(state, "+1555", "Hi", 1000L, now + 1)

        assertFalse(result.enqueued)
        assertEquals("duplicate_in_queue", result.reason)
    }

    @Test
    fun enqueueMessage_skipsRecentlyProcessedMessage() {
        val now = 1_700_000_000_000L
        val messageKey = SmsQueueState.buildMessageKey("+1555", "Hi", 1000L)
        val state = SmsQueueState.QueueStateSnapshot(
            queued = emptyList(),
            processed = listOf(
                SmsQueueState.ProcessedMessage(
                    key = messageKey,
                    processedAt = now,
                )
            )
        )

        val result = SmsQueueState.enqueueMessage(state, "+1555", "Hi", 1000L, now + 1)

        assertFalse(result.enqueued)
        assertEquals("duplicate_recently_processed", result.reason)
    }

    @Test
    fun markAttempted_increasesAttemptCountAndNextEligibleAt() {
        val now = 1_700_000_000_000L
        val messageKey = SmsQueueState.buildMessageKey("+1555", "Hi", 1000L)
        val state = SmsQueueState.QueueStateSnapshot(
            queued = listOf(
                SmsQueueState.QueuedMessage(
                    key = messageKey,
                    address = "+1555",
                    body = "Hi",
                    receivedAt = 1000L,
                    enqueuedAt = now,
                    attemptCount = 0,
                    nextEligibleAt = now,
                )
            ),
            processed = emptyList(),
        )

        val updated = SmsQueueState.markAttempted(state, setOf(messageKey), now)
        val message = updated.queued.single()

        assertEquals(1, message.attemptCount)
        assertTrue(message.nextEligibleAt > now)
    }

    @Test
    fun markProcessed_removesQueuedMessageAndAddsProcessedEntry() {
        val now = 1_700_000_000_000L
        val messageKey = SmsQueueState.buildMessageKey("+1555", "Hi", 1000L)
        val state = SmsQueueState.QueueStateSnapshot(
            queued = listOf(
                SmsQueueState.QueuedMessage(
                    key = messageKey,
                    address = "+1555",
                    body = "Hi",
                    receivedAt = 1000L,
                    enqueuedAt = now,
                    attemptCount = 0,
                    nextEligibleAt = now,
                )
            ),
            processed = emptyList(),
        )

        val updated = SmsQueueState.markProcessed(state, setOf(messageKey), now)

        assertTrue(updated.queued.isEmpty())
        assertEquals(messageKey, updated.processed.single().key)
    }
}
