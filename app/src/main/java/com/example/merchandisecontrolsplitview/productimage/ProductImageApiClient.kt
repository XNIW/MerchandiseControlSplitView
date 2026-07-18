package com.example.merchandisecontrolsplitview.productimage

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal class ProductImageApiClient(
    apiBaseUrl: String,
    storageBaseUrl: String,
    private val debugBuild: Boolean,
    private val http: HttpClient = defaultHttpClient()
) : ProductImageRemoteGateway {
    private val baseUrl = validateBaseUrl(apiBaseUrl)
    private val storageRoot = validateBaseUrl(storageBaseUrl)?.let(::URI)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override val isConfigured: Boolean get() = baseUrl != null && storageRoot != null

    override suspend fun createIntent(
        accessToken: String,
        body: ProductImageIntentBody
    ): ProductImageIntentResponse = postJson("intent", accessToken, body)

    override suspend fun finalizeImage(
        accessToken: String,
        body: ProductImageFinalizeBody
    ): ProductImageFinalizeResponse = postJson("finalize", accessToken, body)

    override suspend fun removeImage(
        accessToken: String,
        body: ProductImageRemoveBody
    ): ProductImageRemoveResponse = postJson("remove", accessToken, body)

    override suspend fun readUrls(
        accessToken: String,
        body: ProductImageReadBody
    ): ProductImageReadResponse = postJson("read-urls", accessToken, body)

    override suspend fun putSignedJpeg(signedUrl: String, bytes: ByteArray) {
        if (bytes.isEmpty() ||
            bytes.size > PRODUCT_IMAGE_MAIN_MAX_BYTES ||
            !isJpeg(bytes) ||
            jpegContainsApp1(bytes)
        ) {
            throw ProductImageException("image_upload_bytes_invalid")
        }
        val safeUrl = validateSignedUrl(signedUrl, upload = true)
        val response = try {
            http.submitFormWithBinaryData(
                url = safeUrl,
                formData = formData {
                    append("cacheControl", "3600")
                    append(
                        "",
                        bytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, ContentType.Image.JPEG.toString())
                            append(HttpHeaders.ContentDisposition, "filename=\"image.jpg\"")
                        }
                    )
                }
            ) {
                method = HttpMethod.Put
                header("x-upsert", "false")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            throw ProductImageException("image_upload_failed")
        }
        if (response.status.value !in 200..299) {
            throw ProductImageException("image_upload_failed_${response.status.value}")
        }
    }

    override suspend fun downloadSignedJpeg(
        signedUrl: String,
        variant: ProductImageVariant
    ): ByteArray {
        val safeUrl = validateSignedUrl(signedUrl, upload = false)
        val response = try {
            http.get(safeUrl) {
                accept(ContentType.Image.JPEG)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            throw ProductImageException("image_download_failed")
        }
        if (response.status.value !in 200..299) {
            throw ProductImageException("image_download_failed_${response.status.value}")
        }
        val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declaredLength != null && declaredLength !in 1..variant.maxBytes.toLong()) {
            throw ProductImageException("image_download_invalid")
        }
        val mediaType = response.headers[HttpHeaders.ContentType]
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        if (mediaType != "image/jpeg") {
            throw ProductImageException("image_download_invalid")
        }
        val bytes = try {
            response.bodyAsBytes()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            throw ProductImageException("image_download_failed")
        }
        if (bytes.isEmpty() ||
            bytes.size > variant.maxBytes ||
            !isJpeg(bytes) ||
            jpegContainsApp1(bytes)
        ) {
            throw ProductImageException("image_download_invalid")
        }
        return bytes
    }

    override fun close() {
        http.close()
    }

    private suspend inline fun <reified Request : Any, reified Response : Any> postJson(
        operation: String,
        accessToken: String,
        body: Request
    ): Response {
        val root = baseUrl ?: throw ProductImageException("image_api_not_configured")
        if (accessToken.isBlank()) throw ProductImageException("image_session_missing")
        val response = try {
            http.post("$root/api/shop/product-images/$operation") {
                bearerAuth(accessToken)
                accept(ContentType.Application.Json)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(body))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            throw ProductImageException("image_request_failed")
        }
        if (response.status.value !in 200..299) {
            throw ProductImageException("image_request_failed_${response.status.value}")
        }
        val text = try {
            response.bodyAsText()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            throw ProductImageException("image_response_invalid")
        }
        if (text.isBlank() || text.encodeToByteArray().size > PRODUCT_IMAGE_API_BODY_MAX_BYTES) {
            throw ProductImageException("image_response_invalid")
        }
        return try {
            json.decodeFromString<Response>(text)
        } catch (_: Throwable) {
            throw ProductImageException("image_response_invalid")
        }
    }

    private fun validateBaseUrl(value: String): String? {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val uri = try {
            URI(trimmed)
        } catch (_: Throwable) {
            throw ProductImageException("image_api_url_invalid")
        }
        if (uri.userInfo != null || uri.query != null || uri.fragment != null || uri.host.isNullOrBlank()) {
            throw ProductImageException("image_api_url_invalid")
        }
        if (!isAllowedTransport(uri)) throw ProductImageException("image_api_url_invalid")
        return trimmed
    }

    private fun validateSignedUrl(value: String, upload: Boolean): String {
        val expectedStorageRoot = storageRoot
            ?: throw ProductImageException("image_signed_url_invalid")
        val uri = try {
            URI(value)
        } catch (_: Throwable) {
            throw ProductImageException("image_signed_url_invalid")
        }
        if (uri.userInfo != null ||
            uri.fragment != null ||
            uri.host.isNullOrBlank() ||
            !isAllowedTransport(uri) ||
            !sameOrigin(uri, expectedStorageRoot)
        ) {
            throw ProductImageException("image_signed_url_invalid")
        }
        val marker = if (upload) {
            "/storage/v1/object/upload/sign/product-images/"
        } else {
            "/storage/v1/object/sign/product-images/"
        }
        val objectPath = uri.rawPath.orEmpty().removePrefix(marker)
        val uuid = PRODUCT_IMAGE_UUID_PATTERN.pattern.removePrefix("^").removeSuffix("$")
        val canonicalPath = Regex(
            "^shops/$uuid/products/$uuid/primary/$uuid/(main|thumb)\\.jpg$",
            RegexOption.IGNORE_CASE
        )
        if (!uri.rawPath.orEmpty().startsWith(marker) || !canonicalPath.matches(objectPath)) {
            throw ProductImageException("image_signed_url_invalid")
        }
        return uri.toASCIIString()
    }

    private fun sameOrigin(left: URI, right: URI): Boolean =
        left.scheme.equals(right.scheme, ignoreCase = true) &&
            left.host.equals(right.host, ignoreCase = true) &&
            effectivePort(left) == effectivePort(right)

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }

    private fun isAllowedTransport(uri: URI): Boolean {
        if (uri.scheme.equals("https", ignoreCase = true)) return true
        if (!debugBuild || !uri.scheme.equals("http", ignoreCase = true)) return false
        return uri.host.equals("127.0.0.1", ignoreCase = true) ||
            uri.host.equals("localhost", ignoreCase = true) ||
            uri.host == "10.0.2.2"
    }

    companion object {
        private fun defaultHttpClient() = HttpClient(OkHttp) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 20_000
                requestTimeoutMillis = 90_000
                socketTimeoutMillis = 90_000
            }
        }
    }
}
