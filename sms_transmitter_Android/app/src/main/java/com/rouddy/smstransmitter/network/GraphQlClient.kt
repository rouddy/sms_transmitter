package com.rouddy.smstransmitter.network

import com.rouddy.smstransmitter.data.PhoneNumberLimitConfig
import com.rouddy.smstransmitter.sms.SentCounts
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single

/**
 * GraphQL HTTP client that communicates with the sms_transmitter_rocket server using Retrofit + RxJava3.
 */
class GraphQlClient internal constructor(private val serviceProvider: () -> GraphQlApiService) {

    constructor(serverUrl: String) : this({
        RetrofitClientProvider.create(serverUrl, GraphQlApiService::class.java)
    })

    private val service: GraphQlApiService by lazy(serviceProvider)

    /**
     * Registers or updates this device's FCM token on the server.
     * Returns a [Single] that emits [Unit] on success or an error on failure.
     */
    fun registerFcmToken(
        token: String,
        deviceInfo: String,
        phoneNumbers: List<PhoneNumberLimitConfig>,
        sentCounts: Map<String, SentCounts> = emptyMap(),
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
                    buildMap {
                        put("phoneNumber", phoneNumber.phoneNumber)
                        put("dailyLimit", phoneNumber.dailyLimit ?: LIMIT_UNLIMITED)
                        put("monthlyLimit", phoneNumber.monthlyLimit ?: LIMIT_UNLIMITED)
                        sentCounts[phoneNumber.phoneNumber]?.let { put("sentCounts", it.toInput()) }
                    }
                },
            )
        )

        return service.registerFcmToken(GraphQlRequest(mutation, variables))
            .requireData()
            .map { data ->
                if (data.registerFcmToken?.id.isNullOrBlank()) {
                    throw GraphQlException("Missing registerFcmToken.id in GraphQL response.")
                }
                Unit
            }
    }

    /**
     * Reports the result of an SMS send attempt to the server, along with the sender's
     * [sentCounts] so the server can sync its counts instead of incrementing them.
     * Returns a [Single] that emits [Unit] on success or an error on failure.
     */
    fun reportSmsResult(
        requestId: String,
        success: Boolean,
        error: String?,
        sentCounts: SentCounts? = null,
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
        if (sentCounts != null) inputMap["sentCounts"] = sentCounts.toInput()

        val variables = mapOf("input" to inputMap)

        return service.reportSmsResult(GraphQlRequest(mutation, variables))
            .requireData()
            .map { data ->
                if (data.reportSmsResult == null) {
                    throw GraphQlException("Missing reportSmsResult in GraphQL response.")
                }
                Unit
            }
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

        return service.deleteFcmToken(GraphQlRequest(mutation, variables))
            .requireData()
            .map { data ->
                if (data.deleteFcmToken == null) {
                    throw GraphQlException("Missing deleteFcmToken in GraphQL response.")
                }
                Unit
            }
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

        return service.fcmTokens(GraphQlRequest(query, emptyMap()))
            .requireData()
            .flatMapMaybe { data ->
                val tokens = data.fcmTokens
                    ?: throw GraphQlException("Missing fcmTokens in GraphQL response.")
                if (tokens.any { it?.id.isNullOrBlank() || it?.token.isNullOrBlank() }) {
                    throw GraphQlException("Invalid fcmTokens entry in GraphQL response.")
                }
                val id = tokens.firstOrNull { it?.token == token }?.id
                if (id != null) Maybe.just(id) else Maybe.empty()
            }
    }

    private fun SentCounts.toInput(): Map<String, Any> = mapOf(
        "countedOn" to countedOn.toString(),
        "dailySentCount" to daily,
        "monthlySentCount" to monthly,
    )

    private fun <T : Any> Single<GraphQlResponse<T>>.requireData(): Single<T> {
        return map { response ->
            if (response.errors?.isNotEmpty() == true) {
                throw GraphQlException("GraphQL errors: ${response.errors}")
            }
            response.data ?: throw GraphQlException("Missing data in GraphQL response.")
        }
    }

    companion object {
        const val LIMIT_UNLIMITED = 0
    }
}

class GraphQlException(message: String) : Exception(message)
