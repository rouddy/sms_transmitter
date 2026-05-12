package com.rouddy.smstransmitter

import io.reactivex.rxjava3.core.Single
import retrofit2.http.Body
import retrofit2.http.POST

data class GraphQlRequest(
    val query: String,
    val variables: Map<String, Any>,
)

interface GraphQlApiService {

    @POST("graphql")
    fun execute(@Body request: GraphQlRequest): Single<GraphQlResponse>
}

data class GraphQlResponse(
    val data: Map<String, Any?>?,
    val errors: List<Map<String, Any?>>?,
)
