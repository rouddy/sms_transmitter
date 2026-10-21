package com.rouddy.smstransmitter.network

import androidx.annotation.VisibleForTesting
import com.rouddy.smstransmitter.BuildConfig
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClientProvider {

    // The server URL rarely changes, but changing it in settings briefly uses both the old
    // URL (token deletion) and the new one, so keep a few recently used clients.
    private const val MAX_CACHED_CLIENTS = 4

    private val cache = object : LinkedHashMap<String, Retrofit>(MAX_CACHED_CLIENTS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Retrofit>?): Boolean =
            size > MAX_CACHED_CLIENTS
    }

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

    /** Cached base URLs, least recently used first. */
    @VisibleForTesting
    @Synchronized
    internal fun cachedBaseUrls(): List<String> = cache.keys.toList()

    @VisibleForTesting
    @Synchronized
    internal fun clearCache() = cache.clear()
}
