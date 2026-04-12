package com.example.smsapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiContractTest {

    @Test
    fun parseMessagesRequest_defaultsToOneWhenBodyIsMissingOrEmpty() {
        assertEquals(1, ApiContract.parseMessagesRequest(null)?.last)
        assertEquals(1, ApiContract.parseMessagesRequest("   ")?.last)
        assertEquals(1, ApiContract.parseMessagesRequest("{}")?.last)
    }

    @Test
    fun parseMessagesRequest_acceptsPositiveIntegersAndMinusOne() {
        assertEquals(5, ApiContract.parseMessagesRequest("{\"last\":5}")?.last)
        assertEquals(-1, ApiContract.parseMessagesRequest("{\"last\":-1}")?.last)
        assertEquals(7, ApiContract.parseMessagesRequest("{\"last\":\"7\"}")?.last)
    }

    @Test
    fun parseMessagesRequest_rejectsInvalidValues() {
        assertNull(ApiContract.parseMessagesRequest("{\"last\":0}"))
        assertNull(ApiContract.parseMessagesRequest("{\"last\":-2}"))
        assertNull(ApiContract.parseMessagesRequest("{\"last\":\"abc\"}"))
        assertNull(ApiContract.parseMessagesRequest("{\"last\":null}"))
        assertNull(ApiContract.parseMessagesRequest("not-json"))
    }
}
