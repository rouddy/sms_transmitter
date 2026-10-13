package com.rouddy.smstransmitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsInputParserTest {
    @Test
    fun `parsePhoneNumbers splits on newlines and commas, normalizes and dedupes`() {
        val phoneNumbers = SettingsInputParser.parsePhoneNumbers("010-1234-5678,\r\n 010 1111 2222\n\n01012345678")
        assertEquals(listOf("01012345678", "01011112222"), phoneNumbers)
    }

    @Test
    fun `parsePhoneNumbers returns empty list for blank input`() {
        assertEquals(emptyList<String>(), SettingsInputParser.parsePhoneNumbers(null))
        assertEquals(emptyList<String>(), SettingsInputParser.parsePhoneNumbers(" \n , "))
    }

    @Test
    fun `parseLimit distinguishes empty, valid and invalid input`() {
        assertEquals(LimitInput.Empty, SettingsInputParser.parseLimit(null))
        assertEquals(LimitInput.Empty, SettingsInputParser.parseLimit("  "))
        assertEquals(LimitInput.Value(30), SettingsInputParser.parseLimit(" 30 "))
        assertEquals(LimitInput.Invalid, SettingsInputParser.parseLimit("0"))
        assertEquals(LimitInput.Invalid, SettingsInputParser.parseLimit("-1"))
        assertEquals(LimitInput.Invalid, SettingsInputParser.parseLimit("abc"))
        assertEquals(LimitInput.Invalid, SettingsInputParser.parseLimit("99999999999"))
    }

    @Test
    fun `toPhoneNumberConfigs maps empty limits to null`() {
        val configs = SettingsInputParser.toPhoneNumberConfigs(
            listOf(
                PhoneNumberLimitDraft("01011112222", dailyLimit = "10", monthlyLimit = ""),
                PhoneNumberLimitDraft("01033334444", dailyLimit = "", monthlyLimit = "300"),
            )
        )
        assertEquals(
            listOf(
                PhoneNumberLimitConfig("01011112222", dailyLimit = 10, monthlyLimit = null),
                PhoneNumberLimitConfig("01033334444", dailyLimit = null, monthlyLimit = 300),
            ),
            configs,
        )
    }

    @Test
    fun `toPhoneNumberConfigs returns null when any limit is invalid`() {
        val configs = SettingsInputParser.toPhoneNumberConfigs(
            listOf(
                PhoneNumberLimitDraft("01011112222", dailyLimit = "10", monthlyLimit = ""),
                PhoneNumberLimitDraft("01033334444", dailyLimit = "", monthlyLimit = "0"),
            )
        )
        assertNull(configs)
    }

    @Test
    fun `parseServerUrl strips a trailing slash`() {
        assertEquals("http://10.0.0.1:8000", SettingsInputParser.parseServerUrl(" http://10.0.0.1:8000/ "))
        assertEquals("https://example.com", SettingsInputParser.parseServerUrl("https://example.com"))
    }

    @Test
    fun `parseServerUrl returns null for blank or malformed input`() {
        assertNull(SettingsInputParser.parseServerUrl(null))
        assertNull(SettingsInputParser.parseServerUrl("  "))
        assertNull(SettingsInputParser.parseServerUrl("example.com"))
        assertNull(SettingsInputParser.parseServerUrl("http://example.com/graphql"))
    }
}
