package com.example.merchandisecontrolsplitview.data

import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.HttpRequest
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.mockk.every
import io.mockk.mockk
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
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
    fun `pagination fail-safe is classified as remote schema unexpected`() {
        val classification = SyncErrorClassifier.classify(
            IllegalStateException(
                "inventory remote fetch exceeded max page iterations (50000) for inventory_products"
            )
        )

        assertEquals(SyncErrorCategory.RemoteSchemaUnexpected, classification.category)
    }

    private fun responseException(status: HttpStatusCode): ClientRequestException {
        val response = mockk<HttpResponse>()
        val call = mockk<HttpClientCall>()
        val request = mockk<HttpRequest>()
        every { response.status } returns status
        every { response.call } returns call
        every { call.request } returns request
        every { request.url } returns Url("https://example.test")
        every { request.method } returns HttpMethod.Get
        return ClientRequestException(response, status.description)
    }
}
