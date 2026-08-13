package com.example.merchandisecontrolsplitview.data

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

enum class AuthProvider {
    GOOGLE,
    WECHAT
}

enum class WeChatSurface(val wireValue: String) {
    ANDROID("android")
}

enum class WeChatAuthError {
    PROVIDER_NOT_CONFIGURED,
    WECHAT_NOT_INSTALLED,
    USER_CANCELLED,
    USER_DENIED,
    CODE_MISSING,
    STATE_MISMATCH,
    CALLBACK_DUPLICATE,
    CALLBACK_EXPIRED,
    BACKEND_ERROR,
    IDENTITY_CONFLICT
}

data class WeChatAuthRequest(
    val state: String,
    val nonce: String,
    val createdAtEpochMillis: Long
) {
    companion object {
        private val secureRandom = SecureRandom()

        fun create(nowEpochMillis: Long = System.currentTimeMillis()): WeChatAuthRequest {
            fun randomBase64Url(): String = ByteArray(32)
                .also(secureRandom::nextBytes)
                .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

            return WeChatAuthRequest(
                state = randomBase64Url(),
                nonce = randomBase64Url(),
                createdAtEpochMillis = nowEpochMillis
            )
        }
    }
}

sealed interface WeChatCodeResult {
    data class Success(val code: String, val state: String) : WeChatCodeResult
    data class Failure(val error: WeChatAuthError) : WeChatCodeResult
    data object Cancelled : WeChatCodeResult
    data object Denied : WeChatCodeResult
    data object NotInstalled : WeChatCodeResult
}

interface WeChatCodeProvider {
    val isConfigured: Boolean
    fun isWeChatInstalled(context: Context): Boolean
    suspend fun requestCode(context: Context, state: String): WeChatCodeResult
}

enum class WeChatCallbackDecision {
    ACCEPT,
    DUPLICATE,
    EXPIRED,
    STATE_MISMATCH
}

class WeChatCallbackGuard(
    private val expectedState: String,
    private val createdAtEpochMillis: Long,
    private val ttlMillis: Long = 5 * 60 * 1_000L
) {
    private var consumed = false

    @Synchronized
    fun consume(callbackState: String, nowEpochMillis: Long): WeChatCallbackDecision {
        if (consumed) return WeChatCallbackDecision.DUPLICATE
        if (nowEpochMillis - createdAtEpochMillis !in 0..ttlMillis) {
            return WeChatCallbackDecision.EXPIRED
        }
        if (!constantTimeEquals(expectedState, callbackState)) {
            return WeChatCallbackDecision.STATE_MISMATCH
        }
        consumed = true
        return WeChatCallbackDecision.ACCEPT
    }
}

internal fun constantTimeEquals(expected: String, actual: String): Boolean =
    MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        actual.toByteArray(Charsets.UTF_8)
    )

data class WeChatChallenge(
    val state: String,
    val nonce: String,
    val correlationId: String,
    val expiresInSeconds: Int
)

data class WeChatSupabaseSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val expiresIn: Long,
    val userId: String
)

sealed interface WeChatGatewayResult<out T> {
    data class Success<T>(val value: T) : WeChatGatewayResult<T>
    data class Failure(val error: WeChatAuthError) : WeChatGatewayResult<Nothing>
}

interface WeChatAuthGateway {
    val isConfigured: Boolean

    suspend fun issueChallenge(
        deviceId: String,
        request: WeChatAuthRequest
    ): WeChatGatewayResult<WeChatChallenge>

    suspend fun exchange(
        challenge: WeChatChallenge,
        code: String,
        deviceId: String
    ): WeChatGatewayResult<WeChatSupabaseSession>
}
