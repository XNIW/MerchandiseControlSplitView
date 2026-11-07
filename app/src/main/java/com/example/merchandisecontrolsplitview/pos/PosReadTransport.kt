package com.example.merchandisecontrolsplitview.pos

import com.example.merchandisecontrolsplitview.data.GenerationOwnedSupabaseClient
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal interface PosReadCall {
    suspend fun await(): PosEnvelope
    fun cancel()
    /** Actual same-call callback/body-close, network terminal event, and dispatcher removal. */
    suspend fun awaitTerminal()
}
internal fun interface PosReadTransport { fun start(request: PosReadRequest): PosReadCall }

/** Short-lived in-memory credentials; never part of UI/cache/diagnostic state. */
internal data class PosCredentials(val endpoint: HttpUrl, val publicKey: String, val accessToken: String)
internal fun interface PosCredentialSource { fun capture(scope: PosScope): PosCredentials }

/** Reads the current SDK session only. A 401 refresh belongs to the existing auth owner, once per flight. */
internal class SupabasePosCredentialSource(
    private val currentClient: () -> SupabaseClient?,
    private val isCurrent: (PosScope) -> Boolean
) : PosCredentialSource {
    override fun capture(scope: PosScope): PosCredentials {
        if (!isCurrent(scope)) throw PosContractException("pos_authority_stale")
        val owner = currentClient() ?: throw PosContractException("pos_auth_missing")
        val client = (owner as? GenerationOwnedSupabaseClient)?.captureClient() ?: owner
        val session = client.auth.currentSessionOrNull() ?: throw PosContractException("pos_auth_missing")
        if (session.user?.id != scope.principalId || session.accessToken.isBlank() || !isCurrent(scope)) throw PosContractException("pos_authority_stale")
        val endpoint = (client.supabaseHttpUrl.trimEnd('/') + "/rest/v1/rpc/mobile_sales_read_v1").toHttpUrl()
        if (endpoint.scheme != "https" || endpoint.username.isNotEmpty() || endpoint.password.isNotEmpty() || endpoint.query != null || endpoint.fragment != null) throw PosContractException("pos_endpoint_invalid")
        if (client.supabaseKey.isBlank()) throw PosContractException("pos_configuration_missing")
        return PosCredentials(endpoint, client.supabaseKey, session.accessToken)
    }
}

/**
 * One POST/one owned OkHttp Call. This read-only adapter avoids Ktor's cancellable-continuation
 * early completion: a cancelled waiter never stands in for physical transport quiescence.
 * The caller supplies the existing foreground scope/epoch predicate and enforces 2s/8s + 1s drain.
 */
internal class OkHttpPosReadTransport(
    private val credentials: PosCredentialSource,
    private val isCurrent: (PosScope) -> Boolean,
    private val baseClient: OkHttpClient,
    private val wallNow: () -> Instant = Instant::now,
    private val newCall: (OkHttpClient, Request) -> Call = { client, request -> client.newCall(request) }
) : PosReadTransport {
    override fun start(request: PosReadRequest): PosReadCall {
        if (!isCurrent(request.scope)) throw PosContractException("pos_authority_stale")
        val captured = credentials.capture(request.scope)
        // A dedicated plain base client is required: hidden application interceptors/authenticators
        // must not retry a logical read or carry credentials to another host.
        if (baseClient.interceptors.isNotEmpty() || baseClient.networkInterceptors.isNotEmpty()) throw PosContractException("pos_http_base_not_owned")
        val result = CompletableDeferred<PosEnvelope>()
        val callbackDone = CompletableDeferred<Unit>()
        val networkTerminal = CompletableDeferred<Unit>()
        val listener = object : EventListener() {
            override fun callEnd(call: Call) { networkTerminal.complete(Unit) }
            override fun callFailed(call: Call, ioe: IOException) { networkTerminal.complete(Unit) }
        }
        val http = baseClient.newBuilder()
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .cache(null).eventListener(listener)
            .callTimeout(if (request.action == PosReadAction.OVERVIEW && request.period == null) 2_000L else 8_000L, TimeUnit.MILLISECONDS)
            .build()
        val body = PosReadCodec.request(request).toRequestBody("application/json; charset=utf-8".toMediaType())
        val httpRequest = Request.Builder().url(captured.endpoint)
            .header("apikey", captured.publicKey).header("Authorization", "Bearer ${captured.accessToken}")
            .header("Accept", "application/json").header("Accept-Profile", "public").header("Content-Profile", "public")
            .post(body).build()
        if (!isCurrent(request.scope)) throw PosContractException("pos_authority_stale")
        val call = newCall(http, httpRequest)
        val callback = object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                try { result.completeExceptionally(e) } finally { callbackDone.complete(Unit) }
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val envelope = response.use { reply ->
                        if (!isCurrent(request.scope) || call.isCanceled()) throw PosContractException("pos_authority_stale")
                        if (!reply.isSuccessful) throw PosHttpException(reply.code, retryAfter(reply.header("Retry-After")))
                        val bytes = boundedBody(reply)
                        if (!isCurrent(request.scope) || call.isCanceled()) throw PosContractException("pos_authority_stale")
                        PosReadCodec.response(bytes, request).also {
                            if (!isCurrent(request.scope) || call.isCanceled()) throw PosContractException("pos_authority_stale")
                        }
                    }
                    result.complete(envelope)
                } catch (failure: Exception) { result.completeExceptionally(failure) }
                finally { callbackDone.complete(Unit) }
            }
        }
        try { call.enqueue(callback) }
        catch (failure: Exception) {
            // No terminal receipt is invented for an enqueue failure. The caller's drain guard
            // will stall this flight if OkHttp cannot attest its actual terminal state.
            call.cancel(); result.completeExceptionally(failure); callbackDone.complete(Unit)
        }
        return object : PosReadCall {
            override suspend fun await(): PosEnvelope = result.await()
            override fun cancel() { call.cancel() }
            override suspend fun awaitTerminal() {
                callbackDone.await()
                networkTerminal.await()
                // RealCall removes itself after Callback returns; both earlier receipts alone
                // can arrive while the dispatcher still owns the callback's final stack frame.
                while (http.dispatcher.queuedCalls().any { it === call } || http.dispatcher.runningCalls().any { it === call }) delay(1)
            }
        }
    }

    private fun boundedBody(response: Response): ByteArray {
        val body = response.body
        val maximum = PosReadCodec.MAXIMUM_RESPONSE_BYTES
        if (body.contentLength() > maximum) throw PosContractException("pos_response_size")
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8_192)
        body.byteStream().use { input ->
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                if (output.size() > maximum - count) throw PosContractException("pos_response_size")
                output.write(chunk, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun retryAfter(raw: String?): Long? {
        if (raw == null || raw.length > 128) return null
        if (Regex("[0-9]+").matches(raw)) return raw.toLongOrNull() ?: Long.MAX_VALUE
        return try {
            val deadline = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            java.time.Duration.between(wallNow(), deadline).seconds.coerceAtLeast(0)
        } catch (failure: RuntimeException) { null }
    }
}
