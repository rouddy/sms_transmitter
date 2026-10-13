package com.rouddy.smstransmitter

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RetrofitClientProviderTest {
    @Before
    fun setUp() = RetrofitClientProvider.clearCache()

    @After
    fun tearDown() = RetrofitClientProvider.clearCache()

    @Test
    fun `reuses the cached client for the same base URL`() {
        RetrofitClientProvider.create(url(1), GraphQlApiService::class.java)
        RetrofitClientProvider.create(url(1), GraphQlApiService::class.java)

        assertEquals(listOf(url(1)), RetrofitClientProvider.cachedBaseUrls())
    }

    @Test
    fun `evicts the least recently used client beyond the limit`() {
        (1..4).forEach { RetrofitClientProvider.create(url(it), GraphQlApiService::class.java) }
        // Touch url(1) so url(2) becomes the least recently used.
        RetrofitClientProvider.create(url(1), GraphQlApiService::class.java)
        RetrofitClientProvider.create(url(5), GraphQlApiService::class.java)

        assertEquals(listOf(url(3), url(4), url(1), url(5)), RetrofitClientProvider.cachedBaseUrls())
    }

    private fun url(index: Int) = "http://10.0.0.$index:8000/"
}
