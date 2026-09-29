package com.hartmann.crosspromo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session semantics without Android context: random UUID-based ids,
 * no user/device data, rotation on demand.
 */
class SessionTest {

    @Test
    fun `newSessionId format is 24 hex-ish chars`() {
        val id = newSessionIdForTest()
        assertEquals(24, id.length)
        assertTrue(id.all { it.isLetterOrDigit() })
    }

    @Test
    fun `rotation produces a new id`() {
        val a = newSessionIdForTest()
        val b = newSessionIdForTest()
        assertNotEquals(a, b)
    }

    private fun newSessionIdForTest(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(24)
}
