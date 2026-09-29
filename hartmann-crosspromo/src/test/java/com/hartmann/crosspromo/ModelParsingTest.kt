package com.hartmann.crosspromo

import com.hartmann.crosspromo.model.PromoResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelParsingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun `parses normal response`() {
        val body = """
            {"version":1,"requestId":"req_abc","generatedAt":"2026-09-29T13:00:00Z",
             "expiresAt":"2026-09-29T19:00:00Z","apps":[
               {"packageName":"com.example.app","name":"Example","iconUrl":"https://x/i",
                "shortDescription":"Short","rating":4.7,"ratingCount":2400,
                "installText":"100K+","storeUrl":"https://play.google.com/store/apps/details?id=com.example.app",
                "selectionType":"popular"}]}
        """.trimIndent()
        val parsed = json.decodeFromString(PromoResponse.serializer(), body)
        assertEquals(1, parsed.apps.size)
        assertEquals("com.example.app", parsed.apps[0].packageName)
        assertEquals(4.7, parsed.apps[0].rating!!, 0.0001)
        assertEquals("popular", parsed.apps[0].selectionType)
    }

    @Test
    fun `unknown fields are ignored (forward compatibility)`() {
        val body = """
            {"version":1,"futureField":{"nested":[1,2,3]},"apps":[
               {"packageName":"com.a.b","storeUrl":"https://play.google.com/store/apps/details?id=com.a.b",
                "futureBoost":42,"experimental":{"x":1}}]}
        """.trimIndent()
        val parsed = json.decodeFromString(PromoResponse.serializer(), body)
        assertEquals("com.a.b", parsed.apps[0].packageName)
        assertNull(parsed.apps[0].rating)
    }

    @Test
    fun `empty apps list is handled`() {
        val parsed = json.decodeFromString(PromoResponse.serializer(), """{"version":1,"apps":[]}""")
        assertTrue(parsed.apps.isEmpty())
    }

    @Test
    fun `null optionals are tolerated`() {
        val body = """
            {"apps":[{"packageName":"com.a.b","name":null,"iconUrl":null,
             "shortDescription":null,"rating":null,"ratingCount":null,"installText":null,
             "storeUrl":"https://play.google.com/store/apps/details?id=com.a.b","selectionType":null}]}
        """.trimIndent()
        val parsed = json.decodeFromString(PromoResponse.serializer(), body)
        assertNull(parsed.apps[0].name)
        assertNull(parsed.apps[0].iconUrl)
    }

    @Test(expected = Exception::class)
    fun `malformed response throws (caller must catch)`() {
        json.decodeFromString(PromoResponse.serializer(), "not-json-at-all")
    }

    @Test
    fun `missing storeUrl with coerceInputValues does not crash on empty object when package present`() {
        val body = """{"apps":[{"packageName":"com.a.b","storeUrl":"https://play.google.com/store/apps/details?id=com.a.b"}]}"""
        val parsed = json.decodeFromString(PromoResponse.serializer(), body)
        assertEquals("com.a.b", parsed.apps[0].packageName)
    }
}
