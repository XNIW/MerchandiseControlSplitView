package com.example.merchandisecontrolsplitview.data

import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.serialization.SerializationException

enum class SyncErrorCategory {
    NetworkOfflineOrTimeout,
    AuthSession,
    RemoteForbiddenRls,
    RemoteNotFoundOrConfig,
    PayloadValidation,
    RemoteSchemaUnexpected,
    Unknown
}

data class SyncErrorClassification(
    val category: SyncErrorCategory,
    val httpStatus: Int? = null,
    val postgrestCode: String? = null
)

object SyncErrorClassifier {

    fun classify(throwable: Throwable): SyncErrorClassification {
        var current: Throwable? = throwable
        while (current != null) {
            val status = current.httpStatus()
            val postgrestCode = (current as? PostgrestRestException)?.code
            if (status != null) {
                return classifyHttpStatus(status, postgrestCode)
            }

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
}
