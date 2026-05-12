package com.rouddy.smstransmitter

import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClientProvider {

    private val cache = mutableMapOf<String, Retrofit>()

    @Synchronized
    fun <T> create(baseUrl: String, serviceClass: Class<T>): T {
        val retrofit = cache.getOrPut(baseUrl) {
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create())
                .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
                .build()
        }
        return retrofit.create(serviceClass)
    }
}
