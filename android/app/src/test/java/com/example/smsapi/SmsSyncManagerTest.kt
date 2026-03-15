package com.example.smsapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsSyncManagerTest {

    @Test
    fun mergeMessages_removesDuplicateMessagePresentInInboxAndExtraMessages() {
        val duplicateMessage = SmsSyncManager.PhoneMessage(
            address = "+447419740587",
            body = "Hello",
            receivedAt = 1_742_006_643_000,
        )

        val mergedMessages = SmsSyncManager.mergeMessages(
            inboxMessages = listOf(duplicateMessage),
            extraMessages = listOf(duplicateMessage),
        )

        assertEquals(1, mergedMessages.size)
        assertEquals(duplicateMessage, mergedMessages.single())
    }

    @Test
    fun mergeMessages_keepsDistinctMessagesSortedNewestFirst() {
        val oldestMessage = SmsSyncManager.PhoneMessage(
            address = "+447419740587",
            body = "First",
            receivedAt = 1000L,
        )
        val newestMessage = SmsSyncManager.PhoneMessage(
            address = "+447419740587",
            body = "Second",
            receivedAt = 2000L,
        )

        val mergedMessages = SmsSyncManager.mergeMessages(
            inboxMessages = listOf(oldestMessage),
            extraMessages = listOf(newestMessage),
        )

        assertEquals(listOf(newestMessage, oldestMessage), mergedMessages)
        assertTrue(mergedMessages.zipWithNext().all { (first, second) -> first.receivedAt >= second.receivedAt })
    }
}
