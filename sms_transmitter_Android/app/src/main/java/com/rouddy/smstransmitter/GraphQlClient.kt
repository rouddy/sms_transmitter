package com.rouddy.smstransmitter

import android.util.Log
import io.reactivex.rxjava3.core.Maybe
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
        phoneNumbers: List<PhoneNumberLimitConfig>,
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
                "phoneNumbers" to phoneNumbers.map { phoneNumber ->
                    mapOf(
                        "phoneNumber" to phoneNumber.phoneNumber,
                        "dailyLimit" to (phoneNumber.dailyLimit ?: LIMIT_UNLIMITED),
                        "monthlyLimit" to (phoneNumber.monthlyLimit ?: LIMIT_UNLIMITED),
                    )
                },
            )
        )

        return execute(mutation, variables)
    }

    /**
     * Reports the result of an SMS send attempt to the server.
     * Returns a [Single] that emits [Unit] on success or an error on failure.
     */
    fun reportSmsResult(
        requestId: String,
        success: Boolean,
        error: String?,
    ): Single<Unit> {
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

    /**
     * Deletes an FCM token from the server by its id.
     * Returns a [Single] that emits [Unit] on success or an error on failure.
     */
    fun deleteFcmToken(tokenId: String): Single<Unit> {
        val mutation = """
            mutation DeleteFcmToken(${'$'}tokenId: ID!) {
              deleteFcmToken(tokenId: ${'$'}tokenId)
            }
        """.trimIndent()

        val variables = mapOf("tokenId" to tokenId)

        return execute(mutation, variables)
    }

    /**
     * Deletes the FCM token whose `token` string matches [token], resolving its
     * server-side id via the `fcmTokens` query first. Completes without error when
     * no matching token exists on the server.
     */
    fun deleteFcmTokenByToken(token: String): Single<Unit> {
        return findFcmTokenId(token)
            .flatMapSingle { tokenId -> deleteFcmToken(tokenId) }
            .switchIfEmpty(Single.just(Unit))
    }

    private fun findFcmTokenId(token: String): Maybe<String> {
        val query = """
            query FcmTokens {
              fcmTokens {
                id
                token
              }
            }
        """.trimIndent()

        return executeQuery(query, emptyMap())
            .flatMapMaybe { data ->
                val tokens = data["fcmTokens"] as? List<*>
                for (item in tokens.orEmpty()) {
                    val fcmToken = item as? Map<*, *> ?: continue
                    if (fcmToken["token"] == token) {
                        val id = fcmToken["id"] as? String
                        if (id != null) return@flatMapMaybe Maybe.just(id)
                    }
                }
                Maybe.empty()
            }
    }

    private fun executeQuery(query: String, variables: Map<String, Any>): Single<Map<String, Any?>> {
        return service.execute(GraphQlRequest(query = query, variables = variables))
            .map { response ->
                if (response.errors?.isNotEmpty() == true) {
                    throw GraphQlException("GraphQL errors: ${response.errors}")
                }
                response.data ?: emptyMap()
            }
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

        const val LIMIT_UNLIMITED = 0
    }
}

class GraphQlException(message: String) : Exception(message)
