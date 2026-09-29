package com.hartmann.crosspromo.attribution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferrerTest {

    @Test
    fun `buildReferrerValue carries utm params`() {
        val v = HartmannInstallAttribution.buildReferrerValue("com.source.app", "com.target.app")
        assertTrue(v.contains("utm_source%3Dcom.source.app"))
        assertTrue(v.contains("utm_medium%3Dcrosspromo"))
        assertTrue(v.contains("utm_campaign%3Dhartmann_crosspromo"))
        assertTrue(v.contains("utm_content%3Dcom.target.app"))
    }

    @Test
    fun `parseReferrer decodes single-encoded params`() {
        val a = HartmannInstallAttribution.parseReferrer(
            "utm_source%3Dcom.source.app%26utm_medium%3Dcrosspromo%26utm_campaign%3Dhartmann_crosspromo%26utm_content%3Dcom.target.app"
        )
        assertEquals("com.source.app", a.sourcePackage)
        assertEquals("crosspromo", a.medium)
        assertEquals("hartmann_crosspromo", a.campaign)
        assertEquals("com.target.app", a.targetPackage)
        assertTrue(a.isCrosspromo)
    }

    @Test
    fun `parseReferrer decodes double-encoded params`() {
        val a = HartmannInstallAttribution.parseReferrer(
            "utm_source%253Dcom.source.app%2526utm_medium%253Dcrosspromo%2526utm_content%253Dcom.target.app"
        )
        assertEquals("com.source.app", a.sourcePackage)
        assertEquals("crosspromo", a.medium)
        assertTrue(a.isCrosspromo)
    }

    @Test
    fun `parseReferrer handles plain key=value pairs`() {
        val a = HartmannInstallAttribution.parseReferrer("utm_source=com.source.app&utm_medium=crosspromo")
        assertEquals("com.source.app", a.sourcePackage)
        assertTrue(a.isCrosspromo)
    }

    @Test
    fun `blank or foreign referrer yields no attribution`() {
        assertFalse(HartmannInstallAttribution.parseReferrer(null).isCrosspromo)
        assertFalse(HartmannInstallAttribution.parseReferrer("").isCrosspromo)
        assertFalse(HartmannInstallAttribution.parseReferrer("utm_source=someadnetwork").isCrosspromo)
        assertNull(HartmannInstallAttribution.parseReferrer(null).targetPackage)
    }
}
