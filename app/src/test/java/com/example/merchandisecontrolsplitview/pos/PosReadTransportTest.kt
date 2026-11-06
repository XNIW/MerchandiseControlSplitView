package com.example.merchandisecontrolsplitview.pos

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** Fake Call callbacks/events only; no DNS, HTTP, Application, SDK instance or database. */
@OptIn(ExperimentalCoroutinesApi::class)
class PosReadTransportTest {
    @Test fun cancellingTheCallDoesNotInventATerminalReceipt() = runTest {
        val fixture = Fixture()
        val terminal = async { fixture.owned.awaitTerminal() }
        runCurrent()
        fixture.owned.cancel()
        verify(exactly = 1) { fixture.call.cancel() }
        runCurrent()
        assertFalse(terminal.isCompleted)
        fixture.callback.onFailure(fixture.call, IOException("cancelled"))
        runCurrent()
        assertFalse("The callback receipt alone is not a network-terminal receipt", terminal.isCompleted)
        fixture.listener.callFailed(fixture.call, IOException("cancelled"))
        runCurrent()
        terminal.await()
    }
    @Test fun networkTerminalBeforeCallbackStillRetainsOwnership() = runTest {
        val fixture = Fixture()
        val terminal = async { fixture.owned.awaitTerminal() }
        fixture.listener.callFailed(fixture.call, IOException("failed"))
        runCurrent()
        assertFalse(terminal.isCompleted)
        fixture.callback.onFailure(fixture.call, IOException("failed"))
        runCurrent()
        terminal.await()
    }
    @Test fun awaitingCancelledResultCannotReleasePhysicalCall() = runTest {
        val fixture = Fixture()
        val waiter = async { fixture.owned.await() }
        runCurrent(); waiter.cancel(); waiter.join()
        val terminal = async { fixture.owned.awaitTerminal() }
        runCurrent()
        assertFalse(terminal.isCompleted)
        fixture.callback.onFailure(fixture.call, IOException("cancelled"))
        fixture.listener.callFailed(fixture.call, IOException("cancelled"))
        runCurrent(); terminal.await()
    }
    @Test fun staleAuthorityAfterCredentialCaptureNeverEnqueues() {
        var current = true
        var created = false
        val transport = OkHttpPosReadTransport(PosCredentialSource {
            current = false
            credentials()
        }, { current }, OkHttpClient(), newCall = { _, _ -> created = true; error("must not create") })
        try { transport.start(request()); fail("Expected stale authority") } catch (expected: PosContractException) {
            assertEquals("pos_authority_stale", expected.code)
        }
        assertFalse(created)
    }
    @Test fun singleRpcUsesCapturedBearerAndPublicKeyAndNoRedirectOrRetry() {
        val fixture = Fixture()
        assertEquals("POST", fixture.httpRequest.method)
        assertEquals("/rest/v1/rpc/mobile_sales_read_v1", fixture.httpRequest.url.encodedPath)
        assertEquals("Bearer synthetic-access-token", fixture.httpRequest.header("Authorization"))
        assertEquals("synthetic-public-key", fixture.httpRequest.header("apikey"))
        assertEquals("public", fixture.httpRequest.header("Content-Profile"))
        assertFalse(fixture.http.retryOnConnectionFailure)
        assertFalse(fixture.http.followRedirects)
        assertFalse(fixture.http.followSslRedirects)
        verify(exactly = 1) { fixture.call.enqueue(any()) }
    }

    private class Fixture {
        val call = mockk<Call>(relaxed = true)
        lateinit var callback: Callback
        lateinit var listener: EventListener
        lateinit var http: OkHttpClient
        lateinit var httpRequest: Request
        val owned: PosReadCall
        init {
            every { call.enqueue(any()) } answers { callback = firstArg() }
            every { call.isCanceled() } returns false
            val transport = OkHttpPosReadTransport(PosCredentialSource { credentials() }, { true }, OkHttpClient(), newCall = { client, request ->
                http = client; httpRequest = request
                listener = client.eventListenerFactory.create(call)
                call
            })
            owned = transport.start(request())
        }
    }
    companion object {
        private fun request() = PosReadRequest(PosScope("10000000-0000-4000-8000-000000000001", "20000000-0000-4000-8000-000000000001", "fixture-device", 1, 0), PosReadAction.OVERVIEW)
        private fun credentials() = PosCredentials("https://fixture.example.test/rest/v1/rpc/mobile_sales_read_v1".toHttpUrl(), "synthetic-public-key", "synthetic-access-token")
    }
}
