package com.rouddy.smstransmitter.network

import io.reactivex.rxjava3.core.Single
import retrofit2.http.Body
import retrofit2.http.POST

data class GraphQlRequest(
    val query: String,
    val variables: Map<String, Any>,
)

interface GraphQlApiService {

    @POST("graphql")
    fun fcmTokens(@Body request: GraphQlRequest): Single<GraphQlResponse<FcmTokensResponse>>

    @POST("graphql")
    fun registerFcmToken(@Body request: GraphQlRequest): Single<GraphQlResponse<RegisterFcmTokenResponse>>

    @POST("graphql")
    fun deleteFcmToken(@Body request: GraphQlRequest): Single<GraphQlResponse<DeleteFcmTokenResponse>>

    @POST("graphql")
    fun reportSmsResult(@Body request: GraphQlRequest): Single<GraphQlResponse<ReportSmsResultResponse>>
}

data class GraphQlResponse<T>(
    val data: T?,
    val errors: List<GraphQlError>?,
)

data class GraphQlError(
    val message: String?,
)

// Nullable wire fields allow missing/null values from Gson to be validated by the client.
data class FcmTokensResponse(
    val fcmTokens: List<FcmTokenDto?>?,
)

data class FcmTokenDto(
    val id: String?,
    val token: String?,
)

data class RegisterFcmTokenResponse(
    val registerFcmToken: RegisteredFcmTokenDto?,
)

data class RegisteredFcmTokenDto(
    val id: String?,
)

data class DeleteFcmTokenResponse(
    val deleteFcmToken: Boolean?,
)

data class ReportSmsResultResponse(
    val reportSmsResult: Boolean?,
)
