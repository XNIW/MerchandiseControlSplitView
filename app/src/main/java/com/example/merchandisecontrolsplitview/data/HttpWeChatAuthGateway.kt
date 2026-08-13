package com.example.merchandisecontrolsplitview.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val MAX_RESPONSE_BYTES = 65_536

class HttpWeChatAuthGateway(
    gatewayBaseUrl: String,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : WeChatAuthGateway {
    private val baseUrl = validatedGatewayBaseUrl(gatewayBaseUrl)
    override val isConfigured: Boolean = baseUrl != null

    override suspend fun issueChallenge(
        deviceId: String,
        request: WeChatAuthRequest
    ): WeChatGatewayResult<WeChatChallenge> {
        val base = baseUrl
            ?: return WeChatGatewayResult.Failure(WeChatAuthError.PROVIDER_NOT_CONFIGURED)
        val response = post(
            URL(base, "/api/auth/wechat/challenge"),
            json.encodeToString(
                ChallengeRequest(
                    deviceId = deviceId,
                    mode = "login",
                    nonce = request.nonce,
                    state = request.state,
                    surface = WeChatSurface.ANDROID.wireValue
                )
            )
        )
        if (response.status !in 200..299) return response.toFailure()
        val envelope = runCatching {
            json.decodeFromString<ChallengeEnvelope>(response.body)
        }.getOrNull() ?: return WeChatGatewayResult.Failure(WeChatAuthError.BACKEND_ERROR)
        val challenge = envelope.challenge
        if (!constantTimeEquals(challenge.state, request.state) ||
            !constantTimeEquals(challenge.nonce, request.nonce)
        ) {
            return WeChatGatewayResult.Failure(WeChatAuthError.STATE_MISMATCH)
        }
        return WeChatGatewayResult.Success(
            WeChatChallenge(
                state = challenge.state,
                nonce = challenge.nonce,
                correlationId = challenge.correlationId,
                expiresInSeconds = challenge.expiresInSeconds
            )
        )
    }

    override suspend fun exchange(
        challenge: WeChatChallenge,
        code: String,
        deviceId: String
    ): WeChatGatewayResult<WeChatSupabaseSession> {
        val base = baseUrl
            ?: return WeChatGatewayResult.Failure(WeChatAuthError.PROVIDER_NOT_CONFIGURED)
        if (code.isBlank() || code.length > 512) {
            return WeChatGatewayResult.Failure(WeChatAuthError.CODE_MISSING)
        }
        val response = post(
            URL(base, "/api/auth/wechat/exchange"),
            json.encodeToString(
                ExchangeRequest(
                    code = code,
                    correlationId = challenge.correlationId,
                    deviceId = deviceId,
                    mode = "login",
                    nonce = challenge.nonce,
                    state = challenge.state,
                    surface = WeChatSurface.ANDROID.wireValue
                )
            )
        )
        if (response.status !in 200..299) return response.toFailure()
        val session = runCatching {
            json.decodeFromString<SessionResponse>(response.body)
        }.getOrNull() ?: return WeChatGatewayResult.Failure(WeChatAuthError.BACKEND_ERROR)
        if (session.accessToken.isBlank() || session.refreshToken.isBlank() ||
            session.user.id.isBlank()
        ) {
            return WeChatGatewayResult.Failure(WeChatAuthError.BACKEND_ERROR)
        }
        return WeChatGatewayResult.Success(
            WeChatSupabaseSession(
                accessToken = session.accessToken,
                refreshToken = session.refreshToken,
                expiresAt = session.expiresAt,
                expiresIn = session.expiresIn,
                userId = session.user.id
            )
        )
    }

    private suspend fun post(url: URL, body: String): HttpResult = withContext(Dispatchers.IO) {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = false
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use(::readBounded) ?: ByteArray(0)
            if (bytes.size > MAX_RESPONSE_BYTES) {
                HttpResult(503, "")
            } else {
                HttpResult(status, bytes.toString(Charsets.UTF_8))
            }
        } catch (_: Throwable) {
            HttpResult(503, "")
        } finally {
            connection.disconnect()
        }
    }

    private fun HttpResult.toFailure(): WeChatGatewayResult.Failure {
        val code = runCatching { json.decodeFromString<ErrorResponse>(body).code }.getOrNull()
        val error = when (code) {
            "provider_not_configured" -> WeChatAuthError.PROVIDER_NOT_CONFIGURED
            "identity_conflict", "identity_already_linked" -> WeChatAuthError.IDENTITY_CONFLICT
            "state_invalid", "state_expired", "state_replayed" -> WeChatAuthError.STATE_MISMATCH
            "code_missing" -> WeChatAuthError.CODE_MISSING
            else -> WeChatAuthError.BACKEND_ERROR
        }
        return WeChatGatewayResult.Failure(error)
    }

    private fun readBounded(stream: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (output.size() <= MAX_RESPONSE_BYTES) {
            val count = stream.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private data class HttpResult(val status: Int, val body: String)

    @Serializable
    private data class ChallengeRequest(
        @SerialName("deviceId") val deviceId: String,
        val mode: String,
        val nonce: String,
        val state: String,
        val surface: String
    )

    @Serializable
    private data class ChallengeEnvelope(val challenge: ChallengeResponse)

    @Serializable
    private data class ChallengeResponse(
        @SerialName("correlationId") val correlationId: String,
        @SerialName("expiresInSeconds") val expiresInSeconds: Int,
        val nonce: String,
        val state: String
    )

    @Serializable
    private data class ExchangeRequest(
        val code: String,
        @SerialName("correlationId") val correlationId: String,
        @SerialName("deviceId") val deviceId: String,
        val mode: String,
        val nonce: String,
        val state: String,
        val surface: String
    )

    @Serializable
    private data class SessionResponse(
        @SerialName("accessToken") val accessToken: String,
        @SerialName("expiresAt") val expiresAt: Long,
        @SerialName("expiresIn") val expiresIn: Long,
        @SerialName("refreshToken") val refreshToken: String,
        val user: SessionUser
    )

    @Serializable
    private data class SessionUser(val id: String)

    @Serializable
    private data class ErrorResponse(val code: String? = null)
}

internal fun validatedGatewayBaseUrl(value: String): URL? {
    val trimmed = value.trim().removeSuffix("/")
    if (trimmed.isEmpty()) return null
    return runCatching {
        val uri = URI(trimmed)
        require(uri.scheme == "https")
        require(uri.host?.isNotBlank() == true)
        require(uri.userInfo == null)
        require(uri.query == null && uri.fragment == null)
        uri.toURL()
    }.getOrNull()
}
