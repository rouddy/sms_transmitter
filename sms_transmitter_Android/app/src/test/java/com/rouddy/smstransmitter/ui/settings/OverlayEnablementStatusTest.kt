package com.rouddy.smstransmitter.ui.settings

import com.rouddy.smstransmitter.data.PhoneNumberLimitConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayEnablementStatusTest {
    private val noLimits = listOf(PhoneNumberLimitConfig("01011112222", dailyLimit = null, monthlyLimit = null))
    private val withLimit = listOf(
        PhoneNumberLimitConfig("01011112222", dailyLimit = null, monthlyLimit = null),
        PhoneNumberLimitConfig("01033334444", dailyLimit = null, monthlyLimit = 300),
    )

    @Test
    fun `requires at least one limit`() {
        assertEquals(OverlayEnablementStatus.LIMITS_MISSING, OverlayEnablementStatus.evaluate(null, hasPermission = true))
        assertEquals(OverlayEnablementStatus.LIMITS_MISSING, OverlayEnablementStatus.evaluate(emptyList(), hasPermission = true))
        assertEquals(OverlayEnablementStatus.LIMITS_MISSING, OverlayEnablementStatus.evaluate(noLimits, hasPermission = true))
    }

    @Test
    fun `checks limits before permission`() {
        assertEquals(OverlayEnablementStatus.LIMITS_MISSING, OverlayEnablementStatus.evaluate(noLimits, hasPermission = false))
        assertEquals(OverlayEnablementStatus.PERMISSION_MISSING, OverlayEnablementStatus.evaluate(withLimit, hasPermission = false))
    }

    @Test
    fun `enabled when a limit is set and permission is granted`() {
        assertEquals(OverlayEnablementStatus.ENABLED, OverlayEnablementStatus.evaluate(withLimit, hasPermission = true))
    }
}
