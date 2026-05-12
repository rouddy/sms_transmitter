package com.rouddy.smstransmitter

import io.reactivex.rxjava3.core.Single
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Url

interface CallbackApiService {

    @GET
    fun get(@Url url: String): Single<ResponseBody>
}
