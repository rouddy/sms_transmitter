package com.rouddy.smstransmitter

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal GraphQL HTTP client for communicating with the sms_transmitter_rocket server.
 * All methods are blocking and must be called from a background thread.
 */
class GraphQlClient(private val serverUrl: String) {

    /**
     * Registers or updates this device's FCM token on the server.
     */
    fun registerFcmToken(
        token: String,
        deviceInfo: String,
        dailyLimit: Int,
        monthlyLimit: Int,
    ) {
        val mutation = """
            mutation RegisterFcmToken(${'$'}input: RegisterFcmTokenInput!) {
              registerFcmToken(input: ${'$'}input) {
                id
              }
            }
        """.trimIndent()

        val variables = JSONObject().apply {
            put("input", JSONObject().apply {
                put("token", token)
                put("deviceInfo", deviceInfo)
                put("dailyLimit", dailyLimit)
                put("monthlyLimit", monthlyLimit)
            })
        }

        execute(mutation, variables)
    }

    /**
     * Reports the result of an SMS send attempt to the server.
     */
    fun reportSmsResult(requestId: String, success: Boolean, error: String?) {
        val mutation = """
            mutation ReportSmsResult(${'$'}input: ReportSmsResultInput!) {
              reportSmsResult(input: ${'$'}input)
            }
        """.trimIndent()

        val variables = JSONObject().apply {
            put("input", JSONObject().apply {
                put("requestId", requestId)
                put("success", success)
                if (error != null) put("error", error)
            })
        }

        execute(mutation, variables)
    }

    private fun execute(query: String, variables: JSONObject) {
        val body = JSONObject().apply {
            put("query", query)
            put("variables", variables)
        }.toString()

        val url = URL(serverUrl)
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true

            connection.outputStream.bufferedWriter().use { it.write(body) }

            val responseCode = connection.responseCode
            val responseBody = runCatching {
                connection.inputStream.bufferedReader().use { it.readText() }
            }.getOrElse {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "GraphQL request failed: HTTP $responseCode — $responseBody")
                return
            }

            val responseJson = runCatching { JSONObject(responseBody) }.getOrNull()
            if (responseJson?.has("errors") == true) {
                Log.e(TAG, "GraphQL errors: ${responseJson.getJSONArray("errors")}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "GraphQL request error", e)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "GraphQlClient"
    }
}
