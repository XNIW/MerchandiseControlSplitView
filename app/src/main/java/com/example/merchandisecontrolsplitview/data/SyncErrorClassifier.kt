package com.example.merchandisecontrolsplitview.data

import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpHeaders
import java.io.IOException
import java.net.UnknownHostException
import java.time.DateTimeException
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import javax.net.ssl.SSLException
import kotlinx.serialization.SerializationException

enum class SyncErrorCategory {
    NetworkOfflineOrTimeout,
    AuthSession,
    RemoteForbiddenRls,
    RemoteNotFoundOrConfig,
    PayloadValidation,
    RemoteSchemaUnexpected,
    Unknown,
    RateLimited,
    RemoteTransient
}

data class SyncErrorClassification(
    val category: SyncErrorCategory,
    val httpStatus: Int? = null,
    val postgrestCode: String? = null,
    val retryAfterAtMs: Long? = null,
    val retryAfterInvalid: Boolean = false
)

object SyncErrorClassifier {

    fun classify(
        throwable: Throwable,
        nowMs: Long = System.currentTimeMillis()
    ): SyncErrorClassification {
        var current: Throwable? = throwable
        // A transport wrapper must not hide a structured auth, permission or server response.
        while (current != null) {
            val status = current.httpStatus()
            val postgrestCode = (current as? PostgrestRestException)?.code
            if (status != null) {
                return classifyHttpStatus(status, postgrestCode).withRetryAfter(current, nowMs)
            }
            current = current.cause
        }

        current = throwable
        while (current != null) {
            when (current) {
                is UnknownHostException,
                is HttpRequestTimeoutException,
                is SSLException,
                is HttpRequestException,
                is IOException -> {
                    return SyncErrorClassification(SyncErrorCategory.NetworkOfflineOrTimeout)
                }
                is SerializationException -> {
                    return SyncErrorClassification(SyncErrorCategory.PayloadValidation)
                }
                is IllegalStateException -> {
                    if (current.message.looksLikeRemoteSchemaIssue()) {
                        return SyncErrorClassification(SyncErrorCategory.RemoteSchemaUnexpected)
                    }
                }
            }
            current = current.cause
        }

        val message = throwable.causeChainMessages()
        return when {
            message.looksLikeRemoteSchemaIssue() ->
                SyncErrorClassification(SyncErrorCategory.RemoteSchemaUnexpected)
            message.contains("jwt") || message.contains("unauthorized") ->
                SyncErrorClassification(SyncErrorCategory.AuthSession)
            message.contains("permission denied") || message.contains("insufficient_privilege") ->
                SyncErrorClassification(SyncErrorCategory.RemoteForbiddenRls)
            else -> SyncErrorClassification(SyncErrorCategory.Unknown)
        }
    }

    fun classifyHttpStatus(
        statusCode: Int,
        postgrestCode: String? = null
    ): SyncErrorClassification {
        val category = when (statusCode) {
            401 -> SyncErrorCategory.AuthSession
            403 -> SyncErrorCategory.RemoteForbiddenRls
            404 -> SyncErrorCategory.RemoteNotFoundOrConfig
            408, 504 -> SyncErrorCategory.NetworkOfflineOrTimeout
            429 -> SyncErrorCategory.RateLimited
            500, 502, 503 -> SyncErrorCategory.RemoteTransient
            400, 422 -> {
                if (postgrestCode != null && postgrestCode in REMOTE_SCHEMA_POSTGREST_CODES) {
                    SyncErrorCategory.RemoteSchemaUnexpected
                } else {
                    SyncErrorCategory.PayloadValidation
                }
            }
            else -> SyncErrorCategory.Unknown
        }
        return SyncErrorClassification(
            category = category,
            httpStatus = statusCode,
            postgrestCode = postgrestCode
        )
    }

    private fun Throwable.httpStatus(): Int? =
        when (this) {
            is RestException -> statusCode
            is ResponseException -> response.status.value
            else -> null
        }

    private fun SyncErrorClassification.withRetryAfter(
        throwable: Throwable,
        nowMs: Long
    ): SyncErrorClassification {
        val response = when (throwable) {
            is RestException -> throwable.response
            is ResponseException -> throwable.response
            else -> return this
        }
        val values = response.headers.getAll(HttpHeaders.RetryAfter) ?: return this
        if (values.size != 1) return copy(retryAfterInvalid = true)
        val value = values.single().trim()
        val deadline = if (value.isNotEmpty() && value.all { it in '0'..'9' }) {
            val seconds = value.toLongOrNull() ?: return copy(retryAfterInvalid = true)
            try {
                Math.addExact(nowMs, Math.multiplyExact(seconds, 1_000L))
            } catch (_: ArithmeticException) {
                return copy(retryAfterInvalid = true)
            }
        } else {
            try {
                ZonedDateTime.parse(value, RETRY_AFTER_HTTP_DATE_FORMAT).toInstant().toEpochMilli()
            } catch (_: DateTimeException) {
                return copy(retryAfterInvalid = true)
            } catch (_: ArithmeticException) {
                return copy(retryAfterInvalid = true)
            }
        }
        // Keep the complete server minimum; local backoff caps belong to the caller.
        return copy(retryAfterAtMs = deadline)
    }

    private fun Throwable.causeChainMessages(): String =
        generateSequence(this) { it.cause }
            .mapNotNull { it.message }
            .joinToString(separator = "\n")
            .lowercase()

    private fun String?.looksLikeRemoteSchemaIssue(): Boolean {
        val text = this?.lowercase().orEmpty()
        return text.contains("inventory remote fetch exceeded max page iterations") ||
            text.contains("fail-safe d-022-p3") ||
            text.contains("schema cache")
    }

    private val REMOTE_SCHEMA_POSTGREST_CODES = setOf("PGRST204", "PGRST205")
    private val RETRY_AFTER_HTTP_DATE_FORMAT = DateTimeFormatter
        .ofPattern("EEE, dd MMM uuuu HH:mm:ss 'GMT'", Locale.US)
        .withResolverStyle(ResolverStyle.STRICT)
        .withZone(ZoneOffset.UTC)
}
