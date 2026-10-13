package com.rouddy.smstransmitter

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClientProvider {

    private val cache = mutableMapOf<String, Retrofit>()

    private val jwtSigner by lazy { JwtSigner.fromPkcs8Base64(BuildConfig.AUTH_PRIVATE_KEY) }

    // Every request carries a freshly signed JWT; the server rejects calls without one.
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Authorization", "Bearer ${jwtSigner.createToken()}")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    @Synchronized
    fun <T> create(baseUrl: String, serviceClass: Class<T>): T {
        val retrofit = cache.getOrPut(baseUrl) {
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
                .build()
        }
        return retrofit.create(serviceClass)
    }
}
