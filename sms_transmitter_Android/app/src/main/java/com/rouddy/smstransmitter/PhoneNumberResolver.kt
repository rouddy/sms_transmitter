package com.rouddy.smstransmitter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

object PhoneNumberResolver {
    fun getAvailablePhoneNumbers(context: Context): List<String> {
        if (!hasPhoneNumberPermission(context)) {
            return emptyList()
        }

        return runCatching {
            val numbers = linkedSetOf<String>()
            val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
            subscriptionManager?.activeSubscriptionInfoList.orEmpty()
                .mapNotNull { it.number }
                .forEach { number ->
                    normalizePhoneNumber(number)?.let { numbers.add(it) }
                }

            if (numbers.isEmpty()) {
                val telephonyManager = context.getSystemService(TelephonyManager::class.java)
                normalizePhoneNumber(telephonyManager?.line1Number)?.let { numbers.add(it) }
            }

            numbers.toList()
        }.getOrDefault(emptyList())
    }

    fun hasPhoneNumberPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * Trims surrounding whitespace, then removes spaces and hyphens. Returns null if nothing is left.
     *
     * Must stay identical to the server's `phone_number::normalize_phone_number`: the server sends
     * the stored number back as `senderPhoneNumber`, which is matched against these settings and the
     * SMS history. Both sides test the same cases.
     */
    fun normalizePhoneNumber(raw: String?): String? {
        val number = raw
            ?.trim()
            ?.replace(" ", "")
            ?.replace("-", "")
            .orEmpty()
        return number.takeIf { it.isNotEmpty() }
    }
}
