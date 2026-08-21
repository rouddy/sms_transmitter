package com.rouddy.smstransmitter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

object PhoneNumberResolver {
    fun getAvailablePhoneNumbers(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            return emptyList()
        }
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

    fun normalizePhoneNumber(raw: String?): String? {
        val number = raw
            ?.trim()
            ?.replace(" ", "")
            ?.replace("-", "")
            .orEmpty()
        return number.takeIf { it.isNotEmpty() }
    }
}
