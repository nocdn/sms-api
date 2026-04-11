package com.example.smsapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiContractTest {

    @Test
    fun parseLastQuery_defaultsToOneWhenMissing() {
        assertEquals(1, ApiContract.parseLastQuery(null))
        assertEquals(1, ApiContract.parseLastQuery("   "))
    }

    @Test
    fun parseLastQuery_acceptsPositiveIntegersAndMinusOne() {
        assertEquals(5, ApiContract.parseLastQuery("5"))
        assertEquals(-1, ApiContract.parseLastQuery("-1"))
    }

    @Test
    fun parseLastQuery_rejectsInvalidValues() {
        assertNull(ApiContract.parseLastQuery("0"))
        assertNull(ApiContract.parseLastQuery("-2"))
        assertNull(ApiContract.parseLastQuery("abc"))
    }
}
