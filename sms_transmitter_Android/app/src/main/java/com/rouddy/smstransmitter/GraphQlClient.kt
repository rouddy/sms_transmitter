package com.rouddy.smstransmitter

import android.util.Log
import io.reactivex.rxjava3.core.Single

/**
 * GraphQL HTTP client that communicates with the sms_transmitter_rocket server using Retrofit + RxJava3.
 */
class GraphQlClient(private val serverUrl: String) {

    private val service: GraphQlApiService by lazy {
        RetrofitClientProvider.create(serverUrl, GraphQlApiService::class.java)
    }

    /**
     * Registers or updates this device's FCM token on the server.
     * Returns a [Single] that emits [Unit] on success or an error on failure.
     */
    fun registerFcmToken(
        token: String,
        deviceInfo: String,
        dailyLimit: Int,
        monthlyLimit: Int,
    ): Single<Unit> {
        val mutation = """
            mutation RegisterFcmToken(${'$'}input: RegisterFcmTokenInput!) {
              registerFcmToken(input: ${'$'}input) {
                id
              }
            }
        """.trimIndent()

        val variables = mapOf(
            "input" to mapOf(
                "token" to token,
                "deviceInfo" to deviceInfo,
                "dailyLimit" to dailyLimit,
                "monthlyLimit" to monthlyLimit,
            )
        )

        return execute(mutation, variables)
    }

    /**
     * Reports the result of an SMS send attempt to the server.
     * Returns a [Single] that emits [Unit] on success or an error on failure.
     */
    fun reportSmsResult(requestId: String, success: Boolean, error: String?): Single<Unit> {
        val mutation = """
            mutation ReportSmsResult(${'$'}input: ReportSmsResultInput!) {
              reportSmsResult(input: ${'$'}input)
            }
        """.trimIndent()

        val inputMap = mutableMapOf<String, Any>(
            "requestId" to requestId,
            "success" to success,
        )
        if (error != null) inputMap["error"] = error

        val variables = mapOf("input" to inputMap)

        return execute(mutation, variables)
    }

    private fun execute(query: String, variables: Map<String, Any>): Single<Unit> {
        return service.execute(GraphQlRequest(query = query, variables = variables))
            .map { response ->
                if (response.errors?.isNotEmpty() == true) {
                    throw GraphQlException("GraphQL errors: ${response.errors}")
                }
                Unit
            }
    }

    companion object {
        private const val TAG = "GraphQlClient"
    }
}

class GraphQlException(message: String) : Exception(message)

