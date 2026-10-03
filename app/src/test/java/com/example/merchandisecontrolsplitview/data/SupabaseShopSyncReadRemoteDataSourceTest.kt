package com.example.merchandisecontrolsplitview.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.ContentType
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ABI regressions for the frozen TASK-139 V6 RPC contract.  These tests are
 * deliberately wire-oriented: a serializer that drops a fence must fail here
 * before it can turn a server divergence into a client noWork result.
 */
class SupabaseShopSyncReadRemoteDataSourceTest {
    private val WIRE_PRIVATE_MARKER = "synthetic-wire-private-response"

    @Test
    fun `checkpoint diagnostic one shot body stops replay after response is lost`() = runBlocking {
        val diagnostic = checkpointWireRun(WireReply.LOST_RESPONSE, diagnostic = true)
        val normal = checkpointWireRun(WireReply.LOST_RESPONSE, diagnostic = false)
        assertTrue("Diagnostic response loss must fail", diagnostic.failed)
        assertEquals(1, diagnostic.requests.size)
        assertTrue("NOT_PROVEN: normal response-loss control did not demonstrate replay", normal.requests.size >= 2)
    }

    @Test
    fun `checkpoint diagnostic 408 cannot retransmit body`() = runBlocking {
        val diagnostic = checkpointWireRun(WireReply.HTTP_408, diagnostic = true)
        val normal = checkpointWireRun(WireReply.HTTP_408, diagnostic = false)
        assertTrue(diagnostic.failed)
        assertEquals(408, diagnostic.status)
        assertEquals(1, diagnostic.requests.size)
        assertEquals("Normal real-engine 408 replay control", 2, normal.requests.size)
    }

    @Test
    fun `checkpoint diagnostic 503 retry after zero cannot retransmit body`() = runBlocking {
        val diagnostic = checkpointWireRun(WireReply.HTTP_503, diagnostic = true)
        val normal = checkpointWireRun(WireReply.HTTP_503, diagnostic = false)
        assertTrue(diagnostic.failed)
        assertEquals(503, diagnostic.status)
        assertEquals(1, diagnostic.requests.size)
        assertEquals("Normal real-engine 503 replay control", 2, normal.requests.size)
    }

    @Test
    fun `checkpoint diagnostic matches original serialized request and traces only checkpoint`() = runBlocking {
        val normal = checkpointWireRun(WireReply.HTTP_500, diagnostic = false)
        val diagnostic = checkpointWireRun(WireReply.HTTP_500, diagnostic = true)
        val n = normal.requests.single()
        val d = diagnostic.requests.single()
        assertTrue("Original JSON bytes must match", n.body.contentEquals(d.body))
        assertTrue("Content-Type including charset must match", ContentType.parse(n.contentType) == ContentType.parse(d.contentType))
        assertEquals(n.declaredBytes, d.declaredBytes)
        assertTrue("Both declared lengths match body bytes", n.declaredBytes == n.body.size && d.declaredBytes == d.body.size)
        assertTrue("Original reader has no trace header", n.trace == null)
        assertTrue("Fixed checkpoint header only", d.trace == CHECKPOINT_TRACE_VALUE)
        assertTrue("Both requests use checkpoint POST", listOf(n, d).all { it.checkpointPost })
        val other = withWireServer(WireReply.HTTP_500) { server, sdk ->
            val reader = SupabaseShopSyncReadRemoteDataSource(sdk)
            assertTrue(reader.convergenceMarker(context(baseline = "0", baselineScopeKey = null)).isFailure)
            assertTrue(reader.checkpoint(context(baseline = "0", baselineScopeKey = null)).isFailure)
            server.snapshots()
        }
        assertEquals(2, other.size)
        assertTrue("Normal reader stays reusable and untraced", other.all { it.trace == null })
        assertTrue("Existing marker function remains distinct", other.first().markerPost)
    }

    @Test
    fun `checkpoint diagnostic rejects another function and second invocation before transmission`() = runBlocking {
        withWireServer(WireReply.HTTP_500) { server, sdk ->
            val attempt = ShopSyncCheckpointTraceAttempt { true }
            val reader = SupabaseShopSyncReadRemoteDataSource(sdk, attempt)
            val ctx = context(baseline = "0", baselineScopeKey = null)
            assertTrue(reader.checkpoint(ctx).isFailure)
            for (failure in listOf(reader.checkpoint(ctx), reader.convergenceMarker(ctx))) {
                assertEquals("checkpoint_trace_rejected", (failure.exceptionOrNull() as? ShopSyncContractException)?.code)
            }
            assertEquals(1, attempt.logicalAttemptCount)
            assertEquals(1, server.snapshots().size)
        }
        withWireServer(WireReply.HTTP_500) { server, sdk ->
            val attempt = ShopSyncCheckpointTraceAttempt { true }
            val failure = SupabaseShopSyncReadRemoteDataSource(sdk, attempt)
                .convergenceMarker(context(baseline = "0", baselineScopeKey = null))
            assertEquals("checkpoint_trace_rejected", (failure.exceptionOrNull() as? ShopSyncContractException)?.code)
            assertEquals(0, attempt.logicalAttemptCount)
            assertTrue(server.snapshots().isEmpty())
        }
    }

    @Test
    fun `checkpoint diagnostic errors do not export response body`() = runBlocking {
        for (reply in listOf(WireReply.HTTP_401, WireReply.HTTP_500, WireReply.MALFORMED, WireReply.OVERSIZE)) {
            val result = checkpointWireRun(reply, diagnostic = true)
            assertTrue("Untrusted response never validates a checkpoint", result.failed)
            assertEquals(1, result.requests.size)
            assertTrue("Only fixed contract diagnostics escape the reader", result.fixedContractFailure)
            assertEquals(if (reply == WireReply.HTTP_401) 401 else if (reply == WireReply.HTTP_500) 500 else 200, result.status)
            if (reply == WireReply.OVERSIZE) assertEquals("rpc_response_budget_exceeded", result.code)
        }
    }

    @Test
    fun `checkpoint diagnostic cancellation after transmission propagates without retry`() = runBlocking {
        withWireServer(WireReply.STALL) { server, sdk ->
            val attempt = ShopSyncCheckpointTraceAttempt { true }
            var propagated = false
            val call = async {
                try { SupabaseShopSyncReadRemoteDataSource(sdk, attempt).checkpoint(context(baseline = "0", baselineScopeKey = null)) }
                catch (cancelled: CancellationException) { propagated = true; throw cancelled }
            }
            withTimeout(3_000) { server.firstBody.await() }
            call.cancel()
            call.join()
            assertTrue("Cancellation must propagate", propagated)
            assertEquals(1, server.snapshots().size)
            assertTrue(attempt.httpStatus == null)
        }
    }

    @Test
    fun `checkpoint diagnostic caller deadline remains failure with unknown response`() = runBlocking {
        withWireServer(WireReply.STALL) { server, sdk ->
            val attempt = ShopSyncCheckpointTraceAttempt { true }
            val call = async {
                SupabaseShopSyncReadRemoteDataSource(sdk, attempt).checkpoint(context(baseline = "0", baselineScopeKey = null))
            }
            try {
                withTimeout(3_000) { server.firstBody.await() }
                val timed = runCatching { withTimeout(200) { call.await() } }
                assertTrue("Bounded external deadline must not validate a response",
                    timed.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
                assertTrue(attempt.httpStatus == null)
                assertEquals(1, server.snapshots().size)
            } finally { call.cancel(); call.join() }
        }
    }

    @Test
    fun `checkpoint diagnostic stale guard refuses before transmission`() = runBlocking {
        withWireServer(WireReply.HTTP_500) { server, sdk ->
            val attempt = ShopSyncCheckpointTraceAttempt { false }
            val result = SupabaseShopSyncReadRemoteDataSource(sdk, attempt).checkpoint(context(baseline = "0", baselineScopeKey = null))
            assertEquals("checkpoint_trace_rejected", (result.exceptionOrNull() as? ShopSyncContractException)?.code)
            assertEquals(0, attempt.logicalAttemptCount)
            assertTrue(server.snapshots().isEmpty())
        }
    }

    private enum class WireReply { LOST_RESPONSE, HTTP_408, HTTP_503, HTTP_401, HTTP_500, MALFORMED, OVERSIZE, STALL }
    private class WireRequest(val body: ByteArray, val declaredBytes: Int, val contentType: String,
        val trace: String?, val checkpointPost: Boolean, val markerPost: Boolean,
        val connectionId: Int)
    private class WireResult(val requests: List<WireRequest>, val status: Int?, val failed: Boolean,
        val fixedContractFailure: Boolean, val code: String?)

    private suspend fun checkpointWireRun(reply: WireReply, diagnostic: Boolean): WireResult =
        withWireServer(reply) { server, sdk ->
            val trace = if (diagnostic) ShopSyncCheckpointTraceAttempt { true } else null
            val reader = if (trace == null) SupabaseShopSyncReadRemoteDataSource(sdk)
                else SupabaseShopSyncReadRemoteDataSource(sdk, trace)
            val result = reader.checkpoint(context(baseline = "0", baselineScopeKey = null))
            val error = result.exceptionOrNull()
            if (reply == WireReply.LOST_RESPONSE) {
                server.assertMeasuredFaultReusesWarmConnection()
                assertTrue("Only measured diagnostic checkpoints carry the trace header",
                    server.snapshots().all { it.trace == if (diagnostic) CHECKPOINT_TRACE_VALUE else null })
            }
            WireResult(server.snapshots(), trace?.httpStatus, result.isFailure,
                error is ShopSyncContractException && !error.toString().contains(WIRE_PRIVATE_MARKER),
                (error as? ShopSyncContractException)?.code)
        }

    @OptIn(io.github.jan.supabase.annotations.SupabaseInternal::class)
    private suspend fun <T> withWireServer(reply: WireReply,
        operation: suspend (WireServer, SupabaseClient) -> T): T {
        val server = WireServer(reply, if (reply == WireReply.LOST_RESPONSE) {
            markerJson(maxId = "0", baseline = "0").toString().toByteArray(Charsets.UTF_8)
        } else null)
        var stored: UserSession? = null
        var ownedSdk: SupabaseClient? = null
        try {
            val sdk = createSupabaseClient("http://127.0.0.1:${server.port}", "synthetic-public-key") {
                httpEngine = OkHttp.create()
                coroutineDispatcher = Dispatchers.IO
                defaultLogLevel = LogLevel.NONE
                requestTimeout = 3.seconds
                install(Auth) {
                    autoLoadFromStorage = false
                    autoSetupPlatform = false
                    sessionManager = object : SessionManager {
                        override suspend fun saveSession(session: UserSession) { stored = session }
                        override suspend fun loadSession(): UserSession = stored ?: error("synthetic_session_absent")
                        override suspend fun deleteSession() { stored = null }
                    }
                    codeVerifierCache = MemoryCodeVerifierCache()
                }
                install(Postgrest) { timeout = 3.seconds }
            }
            ownedSdk = sdk
            sdk.auth.importSession(UserSession(accessToken = "synthetic-access", refreshToken = "synthetic-refresh",
                expiresIn = 86_400, tokenType = "bearer", user = UserInfo(aud = "authenticated", id = ACCOUNT_ID),
                expiresAt = Clock.System.now() + 1.days), autoRefresh = false)
            return withTimeout(5_000) {
                if (reply == WireReply.LOST_RESPONSE) {
                    val warm = SupabaseShopSyncReadRemoteDataSource(sdk)
                        .convergenceMarker(context(baseline = "0", baselineScopeKey = null))
                    assertTrue("Ordinary warm-up RPC must fully decode successfully", warm.isSuccess)
                }
                operation(server, sdk)
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                try { withTimeout(1_000) { ownedSdk?.close() } } finally { server.close() }
            }
        }
    }

    /** Bounded owned loopback only. Request data never leaves RAM or an assertion message. */
    private class WireServer(private val reply: WireReply,
        private val warmResponseBody: ByteArray?) : AutoCloseable {
        private val listener = ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1")).apply { soTimeout = 150 }
        val port: Int get() = listener.localPort
        private val closed = AtomicBoolean(false)
        private val active = AtomicReference<Socket?>(null)
        private val failure = AtomicReference<Throwable?>(null)
        private val requests = Collections.synchronizedList(mutableListOf<WireRequest>())
        private val warmRequest = AtomicReference<WireRequest?>(null)
        private var acceptedConnections = 0
        val firstBody = CompletableDeferred<Unit>()
        private val thread = Thread({ serve() }, "checkpoint-owned-loopback").apply { isDaemon = true; start() }
        fun snapshots(): List<WireRequest> = synchronized(requests) { requests.toList() }
        fun assertMeasuredFaultReusesWarmConnection() {
            val warm = requireNotNull(warmRequest.get()) { "wire_warmup_missing" }
            val measured = snapshots()
            assertTrue("Warm-up is an ordinary untraced marker", warm.markerPost && warm.trace == null)
            assertTrue("Measured fault receives a complete checkpoint body", measured.isNotEmpty())
            assertTrue("Warm-up is excluded from checkpoint counts", measured.all { it.checkpointPost })
            assertEquals("Measured fault must reuse the successfully warmed accepted socket",
                warm.connectionId, measured.first().connectionId)
        }
        private fun serve() {
            try {
                while (!closed.get()) {
                    val socket = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                    active.set(socket)
                    val connectionId = ++acceptedConnections
                    socket.use {
                        socket.soTimeout = 1_000
                        val input = socket.getInputStream()
                        while (!closed.get()) {
                            val header = ByteArrayOutputStream()
                            var suffix = ""
                            while (!suffix.endsWith("\r\n\r\n")) {
                                check(header.size() < 8_192) { "wire_headers_limit" }
                                val byte = input.read(); check(byte >= 0) { "wire_headers_incomplete" }
                                header.write(byte); suffix = (suffix + byte.toChar()).takeLast(4)
                            }
                            val lines = header.toString(Charsets.ISO_8859_1.name()).split("\r\n")
                            val fields = lines.drop(1).filter { ':' in it }.associate {
                                it.substringBefore(':').lowercase() to it.substringAfter(':').trim()
                            }
                            val length = fields["content-length"]?.toIntOrNull()
                            check(length != null && length in 1..16_384) { "wire_body_length" }
                            val body = ByteArray(length)
                            var count = 0
                            while (count < length) {
                                val n = input.read(body, count, length - count)
                                check(n > 0) { "wire_body_incomplete" }; count += n
                            }
                            val requestLine = lines.first()
                            val checkpoint = requestLine == "POST /rest/v1/rpc/shop_sync_recovery_checkpoint_v1 HTTP/1.1"
                            val marker = requestLine == "POST /rest/v1/rpc/shop_sync_convergence_marker_v1 HTTP/1.1"
                            check(checkpoint || marker) { "wire_unexpected_endpoint" }
                            val request = WireRequest(body, length, fields["content-type"].orEmpty(),
                                fields[CHECKPOINT_TRACE_HEADER], checkpoint, marker, connectionId)
                            if (warmResponseBody != null && warmRequest.get() == null) {
                                check(marker && request.trace == null) { "wire_warmup_must_be_ordinary_marker" }
                                warmRequest.set(request)
                                val response = "HTTP/1.1 200 Synthetic\r\nContent-Type: application/json\r\nContent-Length: ${warmResponseBody.size}\r\nConnection: keep-alive\r\n\r\n"
                                socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                                socket.getOutputStream().write(warmResponseBody)
                                socket.getOutputStream().flush()
                                continue
                            }
                            requests += request
                            firstBody.complete(Unit)
                            if (reply == WireReply.STALL) {
                                while (!closed.get() && input.read() >= 0) { /* await owned cancellation */ }
                            } else if (reply == WireReply.LOST_RESPONSE && requests.size == 1) {
                                socket.setSoLinger(true, 0)
                            } else {
                                val first = requests.size == 1
                                val status = when {
                                    reply == WireReply.HTTP_408 && first -> 408
                                    reply == WireReply.HTTP_503 && first -> 503
                                    reply == WireReply.HTTP_401 -> 401
                                    reply == WireReply.MALFORMED || reply == WireReply.OVERSIZE -> 200
                                    else -> 500
                                }
                                val responseBody = if (reply == WireReply.OVERSIZE) "x".repeat(4 * 1024 * 1024 + 1) else "synthetic-wire-private-response"
                                val bytes = responseBody.toByteArray()
                                val retry = if (status == 503) "Retry-After: 0\r\n" else ""
                                val empty = status == 408 || status == 503
                                val response = "HTTP/1.1 $status Synthetic\r\nContent-Type: application/json\r\nContent-Length: ${if (empty) 0 else bytes.size}\r\n${retry}Connection: close\r\n\r\n"
                                socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                                if (!empty) socket.getOutputStream().write(bytes)
                                socket.getOutputStream().flush()
                            }
                            break
                        }
                    }
                    active.set(null)
                }
            } catch (error: Exception) {
                if (!closed.get()) failure.set(error)
            }
        }
        override fun close() {
            closed.set(true)
            listener.close(); active.getAndSet(null)?.close()
            thread.join(1_000)
            check(!thread.isAlive) { "wire_owned_thread_not_released" }
            check(failure.get() == null) { "wire_server_failed" }
        }
    }

    @Test
    fun `short resource exceeded checkpoint returns explicit denial without success sections`() = runTest {
        val source = SupabaseShopSyncReadRemoteDataSource(
            RecordingInvoker { _, _ -> shortCheckpointJson("resource_exceeded") }
        )
        val error = source.checkpoint(shortCheckpointContext("resource_exceeded")).exceptionOrNull()
        assertTrue(error.toString(), error is ShopSyncContractException)
        assertEquals("checkpoint_resource_exceeded", (error as ShopSyncContractException).code)
    }

    @Test
    fun `short invalid baseline checkpoint returns explicit denial without success sections`() = runTest {
        val source = SupabaseShopSyncReadRemoteDataSource(
            RecordingInvoker { _, _ -> shortCheckpointJson("invalid_baseline") }
        )
        val error = source.checkpoint(shortCheckpointContext("invalid_baseline")).exceptionOrNull()
        assertTrue(error.toString(), error is ShopSyncContractException)
        assertEquals("checkpoint_invalid_baseline", (error as ShopSyncContractException).code)
    }

    @Test
    fun `denial envelope validates account device shop schema and scope before status`() = runTest {
        val payload = shortCheckpointJson("resource_exceeded")
        val scope = payload.getValue("scope").jsonObject
        val mismatches = listOf(
            JsonObject(payload + ("shopId" to JsonPrimitive(ACCOUNT_ID))) to "response_shop_mismatch",
            JsonObject(payload + ("schemaVersion" to JsonPrimitive("other"))) to "schema_version_mismatch",
            JsonObject(payload + ("scope" to JsonObject(scope + ("accountKey" to JsonPrimitive(DIGEST))))) to "scope_account_identity_mismatch",
            JsonObject(payload + ("scope" to JsonObject(scope + ("deviceKey" to JsonPrimitive(DIGEST))))) to "scope_device_identity_mismatch",
            JsonObject(payload + ("scope" to JsonObject(scope + ("key" to JsonPrimitive(DIGEST))))) to "baseline_scope_key_mismatch"
        )
        mismatches.forEach { (response, code) ->
            val source = SupabaseShopSyncReadRemoteDataSource(RecordingInvoker { _, _ -> response })
            val error = source.checkpoint(shortCheckpointContext("resource_exceeded")).exceptionOrNull()
            assertEquals(error.toString(), code, (error as ShopSyncContractException).code)
        }
    }

    @Test
    fun `ready checkpoint cannot use short denial envelope or omit status`() = runTest {
        val payload = shortCheckpointJson("resource_exceeded")
        listOf(
            JsonObject(payload + ("status" to JsonPrimitive("ready"))) to setOf("catalog", "prices", "history", "images", "integrity"),
            JsonObject(payload - "status") to setOf("status")
        ).forEach { (response, fields) ->
            val error = SupabaseShopSyncReadRemoteDataSource(RecordingInvoker { _, _ -> response })
                .checkpoint(shortCheckpointContext("resource_exceeded")).exceptionOrNull() as ShopSyncContractException
            assertEquals("rpc_response_missing_fields", error.code)
            assertEquals("shop_sync_recovery_checkpoint_v1", error.rpcName)
            assertEquals(fields, error.missingFields.toSet())
        }
    }

    @Test
    fun `integrity and unknown status stay explicit without echoing untrusted status`() = runTest {
        listOf("integrity_blocked" to "checkpoint_integrity_blocked", "PRIVATE_STATUS_VALUE" to "checkpoint_status_unsupported")
            .forEach { (status, code) ->
                val payload = JsonObject(checkpointJson() + ("status" to JsonPrimitive(status)))
                val error = SupabaseShopSyncReadRemoteDataSource(RecordingInvoker { _, _ -> payload })
                    .checkpoint(context()).exceptionOrNull() as ShopSyncContractException
                assertEquals(code, error.code)
                assertFalse(error.toString().contains("PRIVATE_STATUS_VALUE"))
            }
    }

    @Test
    fun `marker derived from short checkpoint refuses null success sections before decoding them`() = runTest {
        val payload = shortCheckpointJson("resource_exceeded")
        val marker = JsonObject(payload + mapOf(
            "schemaVersion" to JsonPrimitive("shop-sync-convergence-marker-v1"),
            "catalog" to kotlinx.serialization.json.JsonNull,
            "prices" to kotlinx.serialization.json.JsonNull,
            "history" to kotlinx.serialization.json.JsonNull,
            "images" to kotlinx.serialization.json.JsonNull,
            "integrity" to parseObject("""{"totalViolationCount":null}"""),
            "serverNoWorkEligible" to JsonPrimitive(false)
        ))
        val error = SupabaseShopSyncReadRemoteDataSource(RecordingInvoker { _, _ -> marker })
            .convergenceMarker(shortCheckpointContext("resource_exceeded")).exceptionOrNull() as ShopSyncContractException
        assertEquals("convergence_marker_resource_exceeded", error.code)
        assertEquals("shop_sync_convergence_marker_v1", error.rpcName)
    }

    @Test
    fun `resource-limit overrides can narrow but never widen V6 domain caps`() {
        listOf(
            { DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS.copy(supplierPageRows = 241) },
            { DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS.copy(categoryPageRows = 241) },
            { DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS.copy(productPageRows = 61) },
            { DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS.copy(pricePageRows = 121) },
            { DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS.copy(historyPageRows = 4) },
            { DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS.copy(imagePageRows = 241) }
        ).forEach { construct ->
            try {
                construct()
                fail("V6 cap override must be rejected")
            } catch (_: IllegalArgumentException) {
                // Expected: callers may only narrow a negotiated domain cap.
            }
        }
        assertEquals(
            59,
            DEFAULT_SHOP_SYNC_RECOVERY_RESOURCE_LIMITS.copy(productPageRows = 59)
                .pageRows(ShopSyncRowDomain.PRODUCTS)
        )
    }

    @Test
    fun `recovery-only price canonical is absent from ordinary price upsert payload`() {
        val recoveryRow = InventoryProductPriceRow(
            id = PRODUCT_ID,
            ownerUserId = ACCOUNT_ID,
            shopId = SHOP_ID,
            productId = PRODUCT_ID,
            type = "RETAIL",
            price = 7.0,
            priceCanonical = "7",
            effectiveAt = "2026-07-21 10:00:00",
            source = "REMOTE",
            createdAt = "2026-07-21 10:00:00",
            updatedAt = "2026-07-21T10:00:00.000000Z"
        )

        val payload = Json.Default.encodeToJsonElement(
            InventoryProductPriceWriteRow.serializer(),
            recoveryRow.toWriteRow()
        ).jsonObject

        assertFalse(payload.containsKey("price_canonical"))
        assertFalse(payload.containsKey("updated_at"))
        assertEquals("7", recoveryRow.priceCanonical)
        assertEquals("RETAIL", payload.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `recovery reader rejects UUIDv7 and nil entity cursors before invoking RPC`() = runTest {
        listOf(POSTGRES_UUID_V7, POSTGRES_NIL_UUID).forEach { remoteId ->
            val invoker = RecordingInvoker { _, _ -> error("RPC must not receive unsupported entity ID") }
            val source = SupabaseShopSyncReadRemoteDataSource(invoker)

            val page = source.recoveryPage(
                fencedContext(ShopSyncRowDomain.PRODUCTS),
                ShopSyncRowDomain.PRODUCTS,
                afterId = remoteId,
                limit = 60
            )
            assertEquals(
                "page_cursor_invalid",
                (page.exceptionOrNull() as ShopSyncContractException).code
            )
            assertTrue(invoker.calls.isEmpty())

            val targeted = source.rowsByIds(
                fencedContext(ShopSyncRowDomain.PRODUCTS),
                ShopSyncRowDomain.PRODUCTS,
                listOf(remoteId)
            )
            assertEquals(
                "targeted_id_invalid",
                (targeted.exceptionOrNull() as ShopSyncContractException).code
            )
            assertTrue(invoker.calls.isEmpty())
        }
    }

    @Test
    fun `v6 checkpoint and marker send canonical baseline and opaque scope key`() = runTest {
        val invoker = RecordingInvoker { function, _ ->
            when (function) {
                "shop_sync_recovery_checkpoint_v1" -> checkpointJson(
                    maxId = "9007199254740993",
                    baseline = "42"
                )
                "shop_sync_convergence_marker_v1" -> markerJson(
                    maxId = "9007199254740993",
                    baseline = "9007199254740993"
                )
                else -> error("unexpected rpc $function")
            }
        }
        val source = SupabaseShopSyncReadRemoteDataSource(invoker)
        val scope = scope()
        val checkpoint = source.checkpoint(
            context(
                expectedScope = scope,
                baseline = "42",
                baselineScopeKey = scope.key
            )
        ).getOrThrow()
        assertEquals("9007199254740993", checkpoint.syncEvents.maxId)
        assertEquals(9_007_199_254_740_993L, parseShopSyncMaxEventId(checkpoint.syncEvents.maxId))

        val checkpointParams = invoker.calls.first().second
        assertEquals(
            setOf(
                "p_shop_id",
                "p_device_identifier",
                "p_verified_baseline_id",
                "p_expected_baseline_scope_key"
            ),
            checkpointParams.keys
        )
        assertEquals("42", checkpointParams.getValue("p_verified_baseline_id").jsonPrimitive.content)
        assertEquals(SCOPE_KEY, checkpointParams.getValue("p_expected_baseline_scope_key").jsonPrimitive.content)

        val marker = source.convergenceMarker(
            context(
                expectedScope = scope,
                baseline = "9007199254740993",
                baselineScopeKey = scope.key
            )
        ).getOrThrow()
        assertTrue(marker.serverNoWorkEligible)
        assertEquals("shop_sync_convergence_marker_v1", invoker.calls.last().first)
        assertEquals(
            "9007199254740993",
            invoker.calls.last().second.getValue("p_verified_baseline_id").jsonPrimitive.content
        )
    }

    @Test
    fun `v6 reader rejects a scope key bound to another account or device`() = runTest {
        val wrongAccount = scope(accountKey = "a".repeat(64))
        val wrongDevice = scope(deviceKey = "b".repeat(64))

        val accountResult = SupabaseShopSyncReadRemoteDataSource(
            RecordingInvoker { _, _ -> checkpointJson(scope = wrongAccount) }
        ).checkpoint(context())
        val deviceResult = SupabaseShopSyncReadRemoteDataSource(
            RecordingInvoker { _, _ -> checkpointJson(scope = wrongDevice) }
        ).checkpoint(context())

        assertEquals(
            "scope_account_identity_mismatch",
            (accountResult.exceptionOrNull() as ShopSyncContractException).code
        )
        assertEquals(
            "scope_device_identity_mismatch",
            (deviceResult.exceptionOrNull() as ShopSyncContractException).code
        )
    }

    @Test
    fun `v6 recovery page sends all fences and clamps product page to sixty`() = runTest {
        val invoker = RecordingInvoker { function, _ ->
            assertEquals("shop_sync_recovery_page_v1", function)
            recoveryPageJson(domain = "products", limit = 60)
        }
        val source = SupabaseShopSyncReadRemoteDataSource(invoker)

        source.recoveryPage(
            context = fencedContext(ShopSyncRowDomain.PRODUCTS),
            domain = ShopSyncRowDomain.PRODUCTS,
            afterId = null,
            limit = 250
        ).getOrThrow()

        val params = invoker.calls.single().second
        assertEquals(60, params.getValue("p_limit").jsonPrimitive.content.toInt())
        assertEquals(SCOPE_KEY, params.getValue("p_expected_scope_key").jsonPrimitive.content)
        assertEquals("42", params.getValue("p_expected_event_max_id").jsonPrimitive.content)
        assertEquals("42", params.getValue("p_expected_domain_event_max_id").jsonPrimitive.content)
        assertTrue(params.containsKey("p_after_id"))
    }

    @Test
    fun `v6 targeted calls require fences and enforce history cap three`() = runTest {
        val neverCalled = RecordingInvoker { _, _ -> error("must not call") }
        val source = SupabaseShopSyncReadRemoteDataSource(neverCalled)
        val tooMany = source.rowsByIds(
            fencedContext(ShopSyncRowDomain.HISTORY),
            ShopSyncRowDomain.HISTORY,
            listOf(PRODUCT_ID, SUPPLIER_ID, MISSING_ID, CATEGORY_ID)
        )
        assertEquals("targeted_ids_count_invalid", (tooMany.exceptionOrNull() as ShopSyncContractException).code)
        assertTrue(neverCalled.calls.isEmpty())

        val invoker = RecordingInvoker { function, _ ->
            assertEquals("shop_sync_rows_by_ids_v1", function)
            targetedJson(domain = "history", requestedIds = listOf(PRODUCT_ID))
        }
        SupabaseShopSyncReadRemoteDataSource(invoker).rowsByIds(
            fencedContext(ShopSyncRowDomain.HISTORY),
            ShopSyncRowDomain.HISTORY,
            listOf(PRODUCT_ID)
        ).getOrThrow()
        val params = invoker.calls.single().second
        assertEquals("42", params.getValue("p_expected_event_max_id").jsonPrimitive.content)
        assertEquals("42", params.getValue("p_expected_domain_event_max_id").jsonPrimitive.content)
    }

    @Test
    fun `v6 event page uses string bigint ids bootstrap fence and continuation fence`() = runTest {
        val invoker = RecordingInvoker { function, params ->
            assertEquals("shop_sync_event_page_v1", function)
            if (params.getValue("p_after_id").jsonPrimitive.content == "0") {
                eventPageJson(id = "9007199254740993", asOf = "9007199254740993")
            } else {
                eventPageJson(id = null, asOf = "9007199254740993")
            }
        }
        val source = SupabaseShopSyncReadRemoteDataSource(invoker)
        val first = source.eventPage(
            context = context(expectedScope = scope()),
            afterId = 0L,
            limit = 150
        ).getOrThrow()
        assertEquals(9_007_199_254_740_993L, first.rows.single().id)
        val firstParams = invoker.calls.first().second
        assertEquals("0", firstParams.getValue("p_after_id").jsonPrimitive.content)
        assertEquals("null", firstParams.getValue("p_expected_event_max_id").toString())
        assertEquals(150, firstParams.getValue("p_limit").jsonPrimitive.content.toInt())

        source.eventPage(
            context = context(expectedScope = scope(), eventMax = first.asOfEventMaxId),
            afterId = first.rows.single().id,
            limit = 150
        ).getOrThrow()
        assertEquals(
            "9007199254740993",
            invoker.calls.last().second.getValue("p_expected_event_max_id").jsonPrimitive.content
        )
    }

    @Test
    fun `v6 event page default is the frozen 150 row contract cap`() = runTest {
        val invoker = RecordingInvoker { _, _ ->
            eventPageJson(id = null, asOf = "0")
        }

        SupabaseShopSyncReadRemoteDataSource(invoker).eventPage(
            context = context(expectedScope = scope()),
            afterId = 0L
        ).getOrThrow()

        assertEquals(
            150,
            invoker.calls.single().second.getValue("p_limit").jsonPrimitive.content.toInt()
        )
    }

    @Test
    fun `v6 event page rejects numeric ids before any local long conversion`() = runTest {
        val source = SupabaseShopSyncReadRemoteDataSource(
            RecordingInvoker { _, _ -> eventPageJson(id = "1", numericId = true) }
        )
        val result = source.eventPage(context(expectedScope = scope()), 0L, 1)
        assertTrue(result.isFailure)
    }

    @Test
    fun `authorized shop plus legacy accepts only allowed legacy rows and history kind`() = runTest {
        val legacyOwner = "00000000-0000-4000-8000-000000000777"
        val compoundScope = scope(
            kind = ShopSyncScopeKinds.AUTHORIZED_SHOP_PLUS_LEGACY,
            historyKind = ShopSyncScopeKinds.AUTHORIZED_SHOP_PLUS_LEGACY,
            legacyOwnerKey = sha256(legacyOwner.lowercase())
        )
        val invoker = RecordingInvoker { _, _ ->
            recoveryPageJson(
                domain = "history",
                limit = 3,
                scope = compoundScope,
                rows = """[{"remote_id":"$PRODUCT_ID","payload_version":1,"timestamp":"2026-07-22 03:04:05","supplier":"S","category":"C","is_manual_entry":false,"data":[["x"]],"owner_user_id":"$legacyOwner","shop_id":null,"updated_at":"2026-07-22T03:04:05.123456Z","deleted_at":null}]"""
            )
        }
        val result = SupabaseShopSyncReadRemoteDataSource(invoker).recoveryPage(
            fencedContext(ShopSyncRowDomain.HISTORY, compoundScope),
            ShopSyncRowDomain.HISTORY,
            null,
            3
        )
        assertTrue(result.isSuccess)
    }

    @Test
    fun `v6 page and targeted calls fail closed locally when a fence is missing`() = runTest {
        val invoker = RecordingInvoker { _, _ -> error("must not call") }
        val source = SupabaseShopSyncReadRemoteDataSource(invoker)
        val page = source.recoveryPage(context(expectedScope = scope()), ShopSyncRowDomain.PRODUCTS, null, 60)
        val rows = source.rowsByIds(
            context(expectedScope = scope()),
            ShopSyncRowDomain.PRODUCTS,
            listOf(PRODUCT_ID)
        )
        assertEquals("page_event_fence_missing", (page.exceptionOrNull() as ShopSyncContractException).code)
        assertEquals("page_event_fence_missing", (rows.exceptionOrNull() as ShopSyncContractException).code)
        assertTrue(invoker.calls.isEmpty())
    }

    @Test
    fun `shared v6 fixture freezes caps wire strings and image read batch`() {
        val fixture = Json.parseToJsonElement(contractFile().readText()).jsonObject
        assertEquals(6, fixture.getValue("schemaVersion").jsonPrimitive.content.toInt())
        assertEquals(150, fixture.getValue("eventPageLimit").jsonPrimitive.content.toInt())
        assertEquals(60, fixture.getValue("recoveryCaps").jsonObject
            .getValue("products").jsonPrimitive.content.toInt())
        assertEquals(3, fixture.getValue("targetedCaps").jsonObject
            .getValue("history").jsonPrimitive.content.toInt())
        assertEquals(16, fixture.getValue("imageReadUrlsMaxRefs").jsonPrimitive.content.toInt())
        assertEquals("0|[1-9][0-9]{0,18}", fixture.getValue("canonicalEventIdPattern").jsonPrimitive.content)
    }

    private fun context(
        expectedScope: ShopSyncScope? = null,
        baseline: String = "0",
        baselineScopeKey: String? = null,
        eventMax: String? = null,
        domainMax: String? = null
    ) = ShopSyncRpcContext(
        accountId = ACCOUNT_ID,
        shopId = SHOP_ID,
        deviceIdentifier = DEVICE_ID,
        expectedScope = expectedScope,
        verifiedBaselineId = baseline,
        expectedBaselineScopeKey = baselineScopeKey,
        expectedEventMaxId = eventMax,
        expectedDomainEventMaxId = domainMax
    )

    private fun fencedContext(
        domain: ShopSyncRowDomain,
        scope: ShopSyncScope = scope()
    ): ShopSyncRpcContext = context(
        expectedScope = scope,
        eventMax = "42",
        domainMax = "42"
    )

    private fun scope(
        kind: String = ShopSyncScopeKinds.SHOP_SCOPED,
        historyKind: String = ShopSyncScopeKinds.SHOP_SCOPED,
        legacyOwnerKey: String? = null,
        accountKey: String = ACCOUNT_KEY,
        deviceKey: String = DEVICE_KEY
    ) = ShopSyncScope(
        kind = kind,
        key = SCOPE_KEY,
        legacyOwnerKey = legacyOwnerKey,
        historyKind = historyKind,
        accountKey = accountKey,
        deviceKey = deviceKey
    )

    private fun fixtureJson(name: String): JsonObject = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("fixtures/$name")
    ).bufferedReader().use { parseObject(it.readText()) }

    private fun shortCheckpointJson(status: String): JsonObject =
        fixtureJson("mobile-recovery-short-${status.replace('_', '-')}-v1.json")

    private fun shortCheckpointContext(status: String): ShopSyncRpcContext {
        val fixture = fixtureJson("mobile-recovery-short-context-v1.json")
        return ShopSyncRpcContext(
            accountId = fixture.getValue("accountId").jsonPrimitive.content,
            shopId = fixture.getValue("shopId").jsonPrimitive.content,
            deviceIdentifier = fixture.getValue("deviceIdentifier").jsonPrimitive.content,
            verifiedBaselineId = fixture.getValue("verifiedBaselineByStatus").jsonObject.getValue(status).jsonPrimitive.content,
            expectedBaselineScopeKey = shortCheckpointJson(status).getValue("scope").jsonObject.getValue("key").jsonPrimitive.content
        )
    }

    private fun checkpointJson(
        maxId: String = "42",
        baseline: String = "0",
        scope: ShopSyncScope = scope()
    ): JsonObject = parseObject(
        """
        {"schemaVersion":"shop-sync-recovery-checkpoint-v1","status":"ready","shopId":"$SHOP_ID",
         "scope":${scopeJson(scope)},"syncEvents":${syncEventsJson(maxId, baseline)},
         "catalog":{"suppliers":${domainJson()},"categories":${domainJson()},"products":${domainJson(identity = true)},"digest":"$DIGEST"},
         "prices":${domainJson()},"history":${domainJson()},"images":${domainJson()},
         "integrity":{"productCategoryViolationCount":0,"productSupplierViolationCount":0,"priceProductViolationCount":0,"primaryImageViolationCount":0,"historyIdViolationCount":0,"totalViolationCount":0},"checkpointDigest":"$DIGEST"}
        """
    )

    private fun markerJson(maxId: String, baseline: String): JsonObject = parseObject(
        """
        {"schemaVersion":"shop-sync-convergence-marker-v1","status":"ready","shopId":"$SHOP_ID",
         "scope":${scopeJson()},"syncEvents":${syncEventsJson(maxId, baseline, requiresFullRecovery = false)},
         "catalog":{"suppliers":${domainJson()},"categories":${domainJson()},"products":${domainJson(identity = true)},"digest":"$DIGEST"},
         "prices":${domainJson()},"history":${domainJson()},"images":${domainJson()},
         "integrity":{"totalViolationCount":0},"checkpointDigest":"$DIGEST","serverNoWorkEligible":true,"markerDigest":"$MARKER_DIGEST"}
        """
    )

    private fun recoveryPageJson(
        domain: String,
        limit: Int,
        scope: ShopSyncScope = scope(),
        rows: String = "[]"
    ): JsonObject = parseObject(
        """
        {"schemaVersion":"shop-sync-recovery-page-v1","shopId":"$SHOP_ID","scope":${scopeJson(scope)},"domain":"$domain",
         "snapshotEventMaxId":"42","currentScopeEventMaxId":"42","baselineDomainEventMaxId":"42","pageDomainEventMaxId":"42",
         "domainScope":"${if (domain == "history") scope.historyKind else scope.kind}","pageLimit":$limit,"rows":$rows,"nextAfterId":null,"hasMore":false}
        """
    )

    private fun targetedJson(domain: String, requestedIds: List<String>): JsonObject = parseObject(
        """
        {"schemaVersion":"shop-sync-rows-by-ids-v1","shopId":"$SHOP_ID","scope":${scopeJson()},"domain":"$domain",
         "asOfEventMaxId":"42","currentScopeEventMaxId":"42","minimumDomainEventMaxId":"42","materializedDomainEventMaxId":"42","domainScope":"shop_scoped",
         "requestedCount":${requestedIds.size},"rows":[],"missingIds":${requestedIds.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}}
        """
    )

    private fun eventPageJson(
        id: String?,
        asOf: String = "1",
        numericId: Boolean = false
    ): JsonObject {
        val rows = if (id == null) {
            "[]"
        } else {
            val encodedId = if (numericId) id else "\"$id\""
            """[{"id":$encodedId,"owner_user_id":"$ACCOUNT_ID","shop_id":"$SHOP_ID","authorized_shop_id":"$SHOP_ID","store_id":null,"domain":"catalog","event_type":"catalog_changed","source":"admin_web","source_device_key":null,"changed_count":0,"entity_ids":null,"requires_full_recovery":false,"timestamp_valid":true,"created_at":"2026-07-22T03:04:05.123456Z","metadata":{}}]"""
        }
        return parseObject(
            """
            {"schemaVersion":"shop-sync-event-page-v1","shopId":"$SHOP_ID","scope":${scopeJson()},"scopeEventMaxId":"$asOf","asOfEventMaxId":"$asOf",
             "asOfDomainEventMaxIds":{"catalog":"$asOf","prices":"$asOf","history":"$asOf"},"pageLimit":150,"rows":$rows,"nextAfterId":null,"hasMore":false}
            """
        )
    }

    private fun syncEventsJson(
        maxId: String,
        baseline: String,
        requiresFullRecovery: Boolean = false
    ) = """{"maxId":"$maxId","verifiedBaselineId":"$baseline","requiresFullRecovery":$requiresFullRecovery,"domainMaxIds":{"catalog":"$maxId","prices":"$maxId","history":"$maxId"}}"""

    private fun domainJson(identity: Boolean = false) =
        """{"activeCount":0,"tombstoneCount":0,"idSetDigest":"$DIGEST","versionDigest":"$DIGEST"${if (identity) ",\"identityDigest\":\"$DIGEST\"" else ""}}"""

    private fun scopeJson(scope: ShopSyncScope = scope()) =
        """{"kind":"${scope.kind}","historyKind":"${scope.historyKind}","key":"${scope.key}","legacyOwnerKey":${scope.legacyOwnerKey?.let { "\"$it\"" } ?: "null"},"accountKey":"${scope.accountKey}","deviceKey":"${scope.deviceKey}"}"""

    private fun parseObject(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject

    private fun contractFile(): File {
        var current: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (current != null) {
            val candidate = current.resolve("contracts/fixtures/task139-sync-recovery-v6.json")
            if (candidate.isFile) return candidate
            current = current.parentFile
        }
        error("task139 sync V6 fixture not found")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private class RecordingInvoker(
        private val response: suspend (String, JsonObject) -> JsonObject
    ) : ShopSyncRpcInvoker {
        val calls = mutableListOf<Pair<String, JsonObject>>()

        override suspend fun call(
            function: String,
            params: JsonObject,
            maximumResponseBytes: Long,
            maximumHistoryRowBytes: Long?
        ): ShopSyncRpcResponse {
            calls += function to params
            val payload = response(function, params)
            return ShopSyncRpcResponse(payload, payload.toString().encodeToByteArray().size.toLong())
        }
    }

    private companion object {
        const val ACCOUNT_ID = "00000000-0000-4000-8000-000000000139"
        const val SHOP_ID = "00000000-0000-4000-8000-000000000140"
        const val PRODUCT_ID = "00000000-0000-4000-8000-000000000141"
        const val SUPPLIER_ID = "00000000-0000-4000-8000-000000000142"
        const val CATEGORY_ID = "00000000-0000-4000-8000-000000000143"
        const val MISSING_ID = "00000000-0000-4000-8000-000000000144"
        const val POSTGRES_UUID_V7 = "018f0ad4-77f2-7c9d-a8be-4f6b9d234567"
        const val POSTGRES_NIL_UUID = "00000000-0000-0000-0000-000000000000"
        const val DEVICE_ID = "device-139"
        const val DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
        const val MARKER_DIGEST = "2222222222222222222222222222222222222222222222222222222222222222"
        const val SCOPE_KEY = "1111111111111111111111111111111111111111111111111111111111111111"
        const val ACCOUNT_KEY =
            "2306c87344a194ed9a6a1fb2949a03c56" +
                "08c6b2755b4943f6cdbd661b0a275b9"
        const val DEVICE_KEY =
            "257e184cfeb7888e6eb749b3ca4b2d64" +
                "4ef4f278bde892714196a3980ded96e6"
    }
}
