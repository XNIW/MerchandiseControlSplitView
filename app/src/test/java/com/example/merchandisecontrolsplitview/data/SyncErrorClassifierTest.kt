package com.example.merchandisecontrolsplitview.data

import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.HttpRequest
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncErrorClassifierTest {

    @Test
    fun `offline timeout and ssl stay in the same UX bucket`() {
        val cases = listOf(
            UnknownHostException("offline"),
            HttpRequestTimeoutException("https://example.test", 1_000L),
            SSLException("tls")
        )

        cases.forEach { throwable ->
            assertEquals(
                SyncErrorCategory.NetworkOfflineOrTimeout,
                SyncErrorClassifier.classify(throwable).category
            )
        }
    }

    @Test
    fun `http statuses map only when status is structured`() {
        assertEquals(
            SyncErrorCategory.AuthSession,
            SyncErrorClassifier.classifyHttpStatus(401).category
        )
        assertEquals(
            SyncErrorCategory.RemoteForbiddenRls,
            SyncErrorClassifier.classifyHttpStatus(403).category
        )
        assertEquals(
            SyncErrorCategory.RemoteNotFoundOrConfig,
            SyncErrorClassifier.classifyHttpStatus(404).category
        )
        assertEquals(
            SyncErrorCategory.NetworkOfflineOrTimeout,
            SyncErrorClassifier.classifyHttpStatus(504).category
        )
    }

    @Test
    fun `ktor response exception exposes reliable status`() {
        val throwable = responseException(HttpStatusCode.Forbidden)

        val classification = SyncErrorClassifier.classify(throwable)

        assertEquals(SyncErrorCategory.RemoteForbiddenRls, classification.category)
        assertEquals(403, classification.httpStatus)
    }

    @Test
    fun `nested structured forbidden status takes precedence over io wrapper`() {
        val throwable = IOException("transport wrapper", responseException(HttpStatusCode.Forbidden))

        val classification = SyncErrorClassifier.classify(throwable)

        assertEquals(SyncErrorCategory.RemoteForbiddenRls, classification.category)
        assertEquals(403, classification.httpStatus)
    }

    @Test
    fun `structured rate limit with retry after beyond local cap is rate limited`() {
        val throwable = responseException(
            HttpStatusCode.TooManyRequests,
            headersOf(HttpHeaders.RetryAfter, "180")
        )

        val classification = SyncErrorClassifier.classify(throwable, nowMs = 10_000L)

        assertEquals("180", throwable.response.headers[HttpHeaders.RetryAfter])
        assertEquals(429, classification.httpStatus)
        assertEquals("RateLimited", classification.category.name)
        assertEquals(190_000L, classification.retryAfterAtMs)
        assertFalse(classification.retryAfterInvalid)
    }

    @Test
    fun `absent retry after keeps local backoff available`() {
        val classification = SyncErrorClassifier.classify(
            responseException(HttpStatusCode.TooManyRequests),
            nowMs = 1_000L
        )

        assertEquals(SyncErrorCategory.RateLimited, classification.category)
        assertEquals(null, classification.retryAfterAtMs)
        assertFalse(classification.retryAfterInvalid)
    }

    @Test
    fun `zero and whitespace delta retry after are valid`() {
        listOf("0", " 0 ").forEach { value ->
            val classification = SyncErrorClassifier.classify(
                responseException(HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, value)),
                nowMs = 1_000L
            )

            assertEquals(value, 1_000L, classification.retryAfterAtMs)
            assertFalse(value, classification.retryAfterInvalid)
        }
    }

    @Test
    fun `http date retry after preserves server minimum beyond local cap`() {
        val classification = SyncErrorClassifier.classify(
            responseException(
                HttpStatusCode.ServiceUnavailable,
                headersOf(HttpHeaders.RetryAfter, "Thu, 01 Jan 1970 00:03:01 GMT")
            ),
            nowMs = 1_000L
        )

        assertEquals(SyncErrorCategory.RemoteTransient, classification.category)
        assertEquals(181_000L, classification.retryAfterAtMs)
        assertFalse(classification.retryAfterInvalid)
    }

    @Test
    fun `elapsed http date remains valid without inventing a future deadline`() {
        val classification = SyncErrorClassifier.classify(
            responseException(
                HttpStatusCode.ServiceUnavailable,
                headersOf(HttpHeaders.RetryAfter, "Thu, 01 Jan 1970 00:03:01 GMT")
            ),
            nowMs = 200_000L
        )

        assertEquals(181_000L, classification.retryAfterAtMs)
        assertFalse(classification.retryAfterInvalid)
    }

    @Test
    fun `malformed negative fractional and impossible date retry after are explicitly invalid`() {
        val values = listOf(
            "-1",
            "+180",
            "1.5",
            "",
            "not-a-delay",
            "180 seconds",
            "Fri, 31 Feb 2026 04:00:00 GMT",
            "Thu, 01 Jan 1970 00:03:01 UTC"
        )

        values.forEach { value -> assertInvalidRetryAfter(value) }
    }

    @Test
    fun `retry after numeric parsing multiplication and addition overflow stay explicitly invalid`() {
        assertInvalidRetryAfter("9223372036854775808")
        assertInvalidRetryAfter(Long.MAX_VALUE.toString())
        assertInvalidRetryAfter("1", nowMs = Long.MAX_VALUE)
    }

    @Test
    fun `multiple retry after values do not select an arbitrary shorter deadline`() {
        val classification = SyncErrorClassifier.classify(
            responseException(
                HttpStatusCode.TooManyRequests,
                headersOf(HttpHeaders.RetryAfter, listOf("120", "180"))
            ),
            nowMs = 1_000L
        )

        assertEquals(SyncErrorCategory.RateLimited, classification.category)
        assertEquals(null, classification.retryAfterAtMs)
        assertTrue(classification.retryAfterInvalid)
    }

    @Test
    fun `supabase response retains retry metadata status and unconfirmed sql code`() {
        val response = responseException(
            HttpStatusCode.BadRequest,
            headersOf(HttpHeaders.RetryAfter, "180")
        ).response
        val throwable = PostgrestRestException(
            message = "rejected request",
            hint = null,
            details = null,
            code = "57014",
            response = response
        )

        val classification = SyncErrorClassifier.classify(throwable, nowMs = 1_000L)

        assertEquals(SyncErrorCategory.PayloadValidation, classification.category)
        assertEquals(400, classification.httpStatus)
        assertEquals("57014", classification.postgrestCode)
        assertEquals(181_000L, classification.retryAfterAtMs)
        assertFalse(classification.retryAfterInvalid)
    }

    @Test
    fun `nested structured rate limit retains its server minimum through io wrapper`() {
        val throwable = IOException(
            "transport wrapper",
            responseException(HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "180"))
        )

        val classification = SyncErrorClassifier.classify(throwable, nowMs = 1_000L)

        assertEquals(SyncErrorCategory.RateLimited, classification.category)
        assertEquals(429, classification.httpStatus)
        assertEquals(181_000L, classification.retryAfterAtMs)
        assertFalse(classification.retryAfterInvalid)
    }

    @Test
    fun `structured internal server error has a transient category`() {
        assertRemoteTransient(HttpStatusCode.InternalServerError)
    }

    @Test
    fun `structured bad gateway has a transient category`() {
        assertRemoteTransient(HttpStatusCode.BadGateway)
    }

    @Test
    fun `structured service unavailable has a transient category`() {
        assertRemoteTransient(HttpStatusCode.ServiceUnavailable)
    }

    @Test
    fun `structured stable and unsupported statuses retain their exact categories`() {
        val cases = listOf(
            HttpStatusCode.BadRequest to SyncErrorCategory.PayloadValidation,
            HttpStatusCode.Unauthorized to SyncErrorCategory.AuthSession,
            HttpStatusCode.Forbidden to SyncErrorCategory.RemoteForbiddenRls,
            HttpStatusCode.NotFound to SyncErrorCategory.RemoteNotFoundOrConfig,
            HttpStatusCode.UnprocessableEntity to SyncErrorCategory.PayloadValidation,
            HttpStatusCode.NotImplemented to SyncErrorCategory.Unknown,
            HttpStatusCode.VersionNotSupported to SyncErrorCategory.Unknown
        )

        cases.forEach { (status, expectedCategory) ->
            val classification = SyncErrorClassifier.classify(responseException(status))

            assertEquals(status.description, status.value, classification.httpStatus)
            assertEquals(status.description, expectedCategory, classification.category)
        }
    }

    @Test
    fun `postgrest schema code on bad request is treated as schema drift`() {
        val classification = SyncErrorClassifier.classifyHttpStatus(
            statusCode = 400,
            postgrestCode = "PGRST204"
        )

        assertEquals(SyncErrorCategory.RemoteSchemaUnexpected, classification.category)
        assertEquals(400, classification.httpStatus)
        assertEquals("PGRST204", classification.postgrestCode)
    }

    @Test
    fun `bare numeric status in message is not parsed as reliable http status`() {
        val classification = SyncErrorClassifier.classify(
            IllegalStateException("request failed with 403")
        )

        assertEquals(SyncErrorCategory.Unknown, classification.category)
    }

    @Test
    fun `transient looking text and unconfirmed sql cancellation stay unknown`() {
        val messages = listOf(
            "request failed with 429 Retry-After: 180",
            "request failed with 500",
            "request failed with 502",
            "request failed with 503",
            "SQLSTATE 57014",
            "57014 canceling statement due to user request",
            "57014 canceling statement due to statement timeout",
            "unclassified remote failure"
        )

        messages.forEach { message ->
            val classification = SyncErrorClassifier.classify(IllegalStateException(message))

            assertEquals(message, SyncErrorCategory.Unknown, classification.category)
            assertEquals(message, null, classification.httpStatus)
            assertEquals(message, null, classification.postgrestCode)
        }
    }

    @Test
    fun `pagination fail-safe is classified as remote schema unexpected`() {
        val classification = SyncErrorClassifier.classify(
            IllegalStateException(
                "inventory remote fetch exceeded max page iterations (50000) for inventory_products"
            )
        )

        assertEquals(SyncErrorCategory.RemoteSchemaUnexpected, classification.category)
    }

    private fun assertInvalidRetryAfter(value: String, nowMs: Long = 1_000L) {
        val classification = SyncErrorClassifier.classify(
            responseException(HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, value)),
            nowMs = nowMs
        )

        assertEquals(value, SyncErrorCategory.RateLimited, classification.category)
        assertEquals(value, null, classification.retryAfterAtMs)
        assertTrue(value, classification.retryAfterInvalid)
    }

    private fun assertRemoteTransient(status: HttpStatusCode) {
        val classification = SyncErrorClassifier.classify(responseException(status))

        assertEquals(status.description, status.value, classification.httpStatus)
        assertEquals(status.description, "RemoteTransient", classification.category.name)
    }

    private fun responseException(
        status: HttpStatusCode,
        headers: Headers = Headers.Empty
    ): ResponseException {
        val response = mockk<HttpResponse>()
        val call = mockk<HttpClientCall>()
        val request = mockk<HttpRequest>()
        every { response.status } returns status
        every { response.headers } returns headers
        every { response.call } returns call
        every { call.request } returns request
        every { request.url } returns Url("https://example.test")
        every { request.headers } returns Headers.Empty
        every { request.method } returns HttpMethod.Get
        return if (status.value >= 500) {
            ServerResponseException(response, status.description)
        } else {
            ClientRequestException(response, status.description)
        }
    }
}
