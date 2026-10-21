package com.rouddy.smstransmitter.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.rouddy.smstransmitter.data.PhoneNumberLimitConfig
import com.rouddy.smstransmitter.sms.SentCounts
import io.reactivex.rxjava3.core.Single
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import io.reactivex.rxjava3.schedulers.Schedulers
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.time.LocalDate
import java.util.ArrayDeque

class GraphQlClientTest {

    @Before
    fun runRxChainsSynchronously() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
    }

    @After
    fun restoreRxSchedulers() {
        RxJavaPlugins.reset()
    }

    @Test
    fun `matching token deletes its server id`() {
        val fixture = Fixture(
            """{"data":{"fcmTokens":[{"id":"other-id","token":"other"},{"id":"target-id","token":"target"}]}}""",
            """{"data":{"deleteFcmToken":true}}""",
        )

        fixture.client.deleteFcmTokenByToken("target").test().assertResult(Unit)

        assertEquals(2, fixture.requests.size)
        assertTrue(fixture.body(0)["query"].asString.contains("query FcmTokens"))
        assertEquals("target-id", fixture.body(1).getAsJsonObject("variables")["tokenId"].asString)
        assertEquals("POST", fixture.requests[1].method())
        assertEquals("/graphql", fixture.requests[1].url().encodedPath())
    }

    @Test
    fun `empty or nonmatching list succeeds without deletion`() {
        for (tokens in listOf("[]", """[{"id":"other-id","token":"other"}]""")) {
            val fixture = Fixture("""{"data":{"fcmTokens":$tokens},"errors":[]}""")

            fixture.client.deleteFcmTokenByToken("target").test().assertResult(Unit)

            assertEquals(1, fixture.requests.size)
        }
    }

    @Test
    fun `missing query data is an error rather than successful no-op`() {
        for (json in listOf("{}", """{"data":null}""", """{"data":{}}""", """{"data":{"fcmTokens":null}}""")) {
            val fixture = Fixture(json)

            fixture.client.deleteFcmTokenByToken("target").test()
                .assertError(GraphQlException::class.java).assertNoValues()

            assertEquals(1, fixture.requests.size)
        }
    }

    @Test
    fun `invalid token entries fail even after a matching entry`() {
        for (entry in listOf("null", "{}", """{"id":"id"}""", """{"token":"target"}""",
            """{"id":null,"token":"other"}""", """{"id":"","token":"other"}""",
            """{"id":"id","token":null}""", """{"id":"id","token":" "}""")) {
            val fixture = Fixture("""{"data":{"fcmTokens":[{"id":"target-id","token":"target"},$entry]}}""")

            fixture.client.deleteFcmTokenByToken("target").test()
                .assertError(GraphQlException::class.java).assertNoValues()

            assertEquals(1, fixture.requests.size)
        }
    }

    @Test
    fun `incompatible response shapes fail Gson conversion`() {
        for (json in listOf(
            """{"data":{"fcmTokens":{}}}""",
            """{"data":{"fcmTokens":[123]}}""",
            """{"data":{"fcmTokens":[{"id":{},"token":"target"}]}}""",
        )) {
            val fixture = Fixture(json)

            fixture.client.deleteFcmTokenByToken("target").test()
                .assertError { true }.assertNoValues()

            assertEquals(1, fixture.requests.size)
        }
    }

    @Test
    fun `GraphQL errors reject partial query data`() {
        val fixture = Fixture("""{"data":{"fcmTokens":[{"id":"id","token":"target"}]},"errors":[{"message":"Denied","extensions":{"code":"FORBIDDEN"}}]}""")

        fixture.client.deleteFcmTokenByToken("target").test()
            .assertError { it is GraphQlException && it.message.orEmpty().contains("Denied") }
            .assertNoValues()

        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun `registration parses id and preserves request variables`() {
        val fixture = Fixture("""{"data":{"registerFcmToken":{"id":"registered-id","extra":"ignored"}}}""")

        fixture.client.registerFcmToken("target", "device", emptyList()).test().assertResult(Unit)

        val request = fixture.body(0)
        assertTrue(request["query"].asString.contains("mutation RegisterFcmToken"))
        val input = request.getAsJsonObject("variables").getAsJsonObject("input")
        assertEquals("target", input["token"].asString)
        assertEquals("device", input["deviceInfo"].asString)
        assertEquals(0, input.getAsJsonArray("phoneNumbers").size())
    }

    @Test
    fun `registration sends sent counts only for numbers that have them`() {
        val fixture = Fixture("""{"data":{"registerFcmToken":{"id":"registered-id"}}}""")
        val phoneNumbers = listOf(
            PhoneNumberLimitConfig("01011112222", dailyLimit = 10, monthlyLimit = null),
            PhoneNumberLimitConfig("01033334444", dailyLimit = null, monthlyLimit = 300),
        )
        val sentCounts = mapOf("01011112222" to SentCounts(LocalDate.of(2026, 10, 14), daily = 3, monthly = 42))

        fixture.client.registerFcmToken("target", "device", phoneNumbers, sentCounts).test().assertResult(Unit)

        val rows = fixture.body(0).getAsJsonObject("variables").getAsJsonObject("input").getAsJsonArray("phoneNumbers")
        val counted = rows[0].asJsonObject
        assertEquals(10, counted["dailyLimit"].asInt)
        assertEquals(0, counted["monthlyLimit"].asInt)
        val counts = counted.getAsJsonObject("sentCounts")
        assertEquals("2026-10-14", counts["countedOn"].asString)
        assertEquals(3, counts["dailySentCount"].asInt)
        assertEquals(42, counts["monthlySentCount"].asInt)
        assertFalse(rows[1].asJsonObject.has("sentCounts"))
    }

    @Test
    fun `registration requires returned id`() {
        for (data in listOf("{}", """{"registerFcmToken":null}""", """{"registerFcmToken":{}}""",
            """{"registerFcmToken":{"id":null}}""", """{"registerFcmToken":{"id":" "}}""")) {
            Fixture("""{"data":$data}""").client.registerFcmToken("target", "device", emptyList())
                .test().assertError(GraphQlException::class.java).assertNoValues()
        }
    }

    @Test
    fun `scalar mutations accept both Boolean values as before`() {
        for (value in listOf(true, false)) {
            Fixture("""{"data":{"deleteFcmToken":$value}}""").client.deleteFcmToken("id")
                .test().assertResult(Unit)
            Fixture("""{"data":{"reportSmsResult":$value}}""").client.reportSmsResult("request-id", true, null)
                .test().assertResult(Unit)
        }
    }

    @Test
    fun `scalar mutations require their response fields`() {
        for (json in listOf("""{"data":{}}""", """{"data":{"deleteFcmToken":null,"reportSmsResult":null}}""")) {
            Fixture(json).client.deleteFcmToken("id").test()
                .assertError(GraphQlException::class.java).assertNoValues()
            Fixture(json).client.reportSmsResult("request-id", true, null).test()
                .assertError(GraphQlException::class.java).assertNoValues()
        }
    }

    @Test
    fun `all mutations reject missing data and GraphQL errors`() {
        val operations: List<(GraphQlClient) -> Single<Unit>> = listOf(
            { it.registerFcmToken("target", "device", emptyList()) },
            { it.deleteFcmToken("id") },
            { it.reportSmsResult("request-id", true, null) },
        )
        for (operation in operations) {
            for (json in listOf("{}", """{"data":null}""",
                """{"data":{"registerFcmToken":{"id":"id"},"deleteFcmToken":true,"reportSmsResult":true},"errors":[{"message":"Denied"}]}""")) {
                operation(Fixture(json).client).test().assertError(GraphQlException::class.java).assertNoValues()
            }
        }
    }

    @Test
    fun `SMS report preserves optional error input`() {
        for (error in listOf(null, "SMS failed")) {
            val fixture = Fixture("""{"data":{"reportSmsResult":true}}""")

            fixture.client.reportSmsResult("request-id", false, error).test().assertResult(Unit)

            val input = fixture.body(0).getAsJsonObject("variables").getAsJsonObject("input")
            assertEquals("request-id", input["requestId"].asString)
            assertFalse(input["success"].asBoolean)
            if (error == null) assertFalse(input.has("error")) else assertEquals(error, input["error"].asString)
        }
    }

    @Test
    fun `SMS report sends sent counts only when given`() {
        val withCounts = Fixture("""{"data":{"reportSmsResult":true}}""")
        withCounts.client.reportSmsResult("request-id", true, null, SentCounts(LocalDate.of(2026, 10, 1), daily = 1, monthly = 1))
            .test().assertResult(Unit)
        val counts = withCounts.body(0).getAsJsonObject("variables").getAsJsonObject("input").getAsJsonObject("sentCounts")
        assertEquals("2026-10-01", counts["countedOn"].asString)
        assertEquals(1, counts["dailySentCount"].asInt)
        assertEquals(1, counts["monthlySentCount"].asInt)

        val withoutCounts = Fixture("""{"data":{"reportSmsResult":true}}""")
        withoutCounts.client.reportSmsResult("request-id", true, null).test().assertResult(Unit)
        assertFalse(withoutCounts.body(0).getAsJsonObject("variables").getAsJsonObject("input").has("sentCounts"))
    }

    @Test
    fun `deletion errors propagate after successful lookup`() {
        val fixture = Fixture(
            """{"data":{"fcmTokens":[{"id":"id","token":"target"}]}}""",
            """{"errors":[{"message":"Delete failed"}]}""",
        )

        fixture.client.deleteFcmTokenByToken("target").test()
            .assertError { it is GraphQlException && it.message.orEmpty().contains("Delete failed") }
            .assertNoValues()

        assertEquals(2, fixture.requests.size)
    }

    @Test
    fun `transport failures propagate without deletion`() {
        val fixture = Fixture()

        fixture.client.deleteFcmTokenByToken("target").test()
            .assertError(IOException::class.java).assertNoValues()

        assertEquals(1, fixture.requests.size)
    }

    // Exercise the real Retrofit/Gson/RxJava pipeline without opening a network connection.
    private class Fixture(vararg responses: String) {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<Request>()
        private val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests.add(chain.request())
                val json = this.responses.pollFirst() ?: throw IOException("No response available")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(null, json))
                    .build()
            }
            .build()
        private val service = Retrofit.Builder()
            .baseUrl("http://localhost/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .addCallAdapterFactory(RxJava3CallAdapterFactory.createSynchronous())
            .validateEagerly(true)
            .build()
            .create(GraphQlApiService::class.java)
        val client = GraphQlClient { service }

        fun body(index: Int): JsonObject {
            val buffer = Buffer()
            requests[index].body()!!.writeTo(buffer)
            return Gson().fromJson(buffer.readUtf8(), JsonObject::class.java)
        }
    }
}
