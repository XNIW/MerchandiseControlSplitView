package com.example.merchandisecontrolsplitview.data

import org.junit.Assert.assertEquals
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import com.tencent.mm.opensdk.modelbase.BaseResp
import java.io.File
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatAuthContractTest {
    @Test
    fun `state and nonce are independent cryptographically sized values`() {
        val request = WeChatAuthRequest.create(nowEpochMillis = 1_000L)

        assertTrue(request.state.length >= 43)
        assertTrue(request.nonce.length >= 43)
        assertNotEquals(request.state, request.nonce)
        assertEquals(1_000L, request.createdAtEpochMillis)
    }

    @Test
    fun `callback accepts matching state exactly once`() {
        val guard = WeChatCallbackGuard("expected", createdAtEpochMillis = 1_000L)

        assertEquals(WeChatCallbackDecision.ACCEPT, guard.consume("expected", 2_000L))
        assertEquals(WeChatCallbackDecision.DUPLICATE, guard.consume("expected", 2_001L))
    }

    @Test
    fun `callback rejects state mismatch without consuming valid callback`() {
        val guard = WeChatCallbackGuard("expected", createdAtEpochMillis = 1_000L)

        assertEquals(WeChatCallbackDecision.STATE_MISMATCH, guard.consume("other", 2_000L))
        assertEquals(WeChatCallbackDecision.ACCEPT, guard.consume("expected", 2_001L))
    }

    @Test
    fun `callback rejects expired and future timestamps`() {
        val expired = WeChatCallbackGuard("expected", createdAtEpochMillis = 1_000L, ttlMillis = 100L)
        val future = WeChatCallbackGuard("expected", createdAtEpochMillis = 2_000L, ttlMillis = 100L)

        assertEquals(WeChatCallbackDecision.EXPIRED, expired.consume("expected", 1_101L))
        assertEquals(WeChatCallbackDecision.EXPIRED, future.consume("expected", 1_999L))
    }

    @Test
    fun `gateway base URL requires HTTPS host and no credentials query or fragment`() {
        assertEquals("https://auth.example.test", validatedGatewayBaseUrl("https://auth.example.test/")?.toString())
        assertNull(validatedGatewayBaseUrl("http://auth.example.test"))
        assertNull(validatedGatewayBaseUrl("https://user:password@auth.example.test"))
        assertNull(validatedGatewayBaseUrl("https://auth.example.test?secret=value"))
        assertNull(validatedGatewayBaseUrl("https://auth.example.test/#fragment"))
    }

    @Test
    fun `pinned Tencent SDK callback accepts code exactly once`() = runTest {
        val broker = WeChatCallbackBroker(nowEpochMillis = { 2_000L })
        val result = async { broker.request("expected") { true } }
        yield()

        assertTrue(
            broker.deliver(
                WeChatOpenSdkCallback(
                    code = "temporary-code",
                    errorCode = BaseResp.ErrCode.ERR_OK,
                    state = "expected"
                )
            )
        )
        assertEquals(WeChatCodeResult.Success("temporary-code", "expected"), result.await())
        assertTrue(
            !broker.deliver(
                WeChatOpenSdkCallback(
                    code = "temporary-code",
                    errorCode = BaseResp.ErrCode.ERR_OK,
                    state = "expected"
                )
            )
        )
        assertEquals("6.8.34", WeChatOpenSdkCodeProvider.SDK_VERSION)
    }

    @Test
    fun `OpenSDK callback rejects mismatched state and cold callback`() = runTest {
        val broker = WeChatCallbackBroker(nowEpochMillis = { 2_000L })
        assertTrue(
            !broker.deliver(
                WeChatOpenSdkCallback(
                    code = "cold-code",
                    errorCode = BaseResp.ErrCode.ERR_OK,
                    state = "cold-state"
                )
            )
        )
        val result = async { broker.request("expected") { true } }
        yield()
        broker.deliver(
            WeChatOpenSdkCallback(
                code = "temporary-code",
                errorCode = BaseResp.ErrCode.ERR_OK,
                state = "wrong"
            )
        )
        assertEquals(
            WeChatCodeResult.Failure(WeChatAuthError.STATE_MISMATCH),
            result.await()
        )
    }

    @Test
    fun `OpenSDK manifest exposes only required callback and declares package visibility`() {
        val candidate = listOf(
            File("app/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml")
        ).first { it.exists() }
        val manifest = candidate.readText()

        assertTrue(manifest.contains(".wxapi.WXEntryActivity"))
        assertTrue(manifest.contains("android:exported=\"true\""))
        assertTrue(manifest.contains("<package android:name=\"com.tencent.mm\""))
        assertTrue(!manifest.contains("AppSecret", ignoreCase = true))
    }
}
