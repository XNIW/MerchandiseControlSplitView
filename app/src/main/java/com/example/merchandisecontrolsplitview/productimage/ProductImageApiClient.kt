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
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readRemaining
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
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
    ): ProductImageReadResponse {
        // Keep the V6 fan-out boundary at the transport entry point too. The
        // service already chunks requests, but a future gateway caller must
        // not be able to bypass the server/client contract or grow a signed
        // URL response past the bounded decoder budget.
        if (body.refs.size !in 1..PRODUCT_IMAGE_READ_URLS_MAX_REFS) {
            throw ProductImageException("image_read_contract_invalid")
        }
        return postJson(
            operation = "read-urls",
            accessToken = accessToken,
            body = body,
            maximumResponseBytes = PRODUCT_IMAGE_READ_URLS_RESPONSE_MAX_BYTES
        )
    }

    override suspend fun putSignedJpeg(
        signedUrl: String,
        bytes: ByteArray,
        expectedReference: ProductImageReference
    ) {
        if (bytes.isEmpty() ||
            bytes.size > expectedReference.variant.maxBytes ||
            !isJpeg(bytes)
        ) {
            throw ProductImageException("image_encode_failed")
        }
        if (jpegContainsForbiddenMetadata(bytes)) {
            throw ProductImageException("image_metadata_forbidden")
        }
        val safeUrl = validateSignedUrl(
            signedUrl,
            upload = true,
            expectedReference = expectedReference
        )
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
            throw ProductImageException("image_upload_failed", response.status.value)
        }
    }

    override suspend fun downloadSignedJpeg(
        signedUrl: String,
        expectedReference: ProductImageReference
    ): ByteArray {
        val variant = expectedReference.variant
        val safeUrl = validateSignedUrl(
            signedUrl,
            upload = false,
            expectedReference = expectedReference
        )
        val response = try {
            http.get(safeUrl) {
                accept(ContentType.Image.JPEG)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            throw ProductImageException("image_request_failed")
        }
        if (response.status.value !in 200..299) {
            throw ProductImageException("image_request_failed", response.status.value)
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
            readProductImageBodyBounded(response.bodyAsChannel(), variant.maxBytes)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ProductImageException) {
            throw error
        } catch (_: Throwable) {
            throw ProductImageException("image_request_failed")
        }
        if (bytes.isEmpty() ||
            bytes.size > variant.maxBytes ||
            !isJpeg(bytes) ||
            jpegContainsForbiddenMetadata(bytes)
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
        body: Request,
        maximumResponseBytes: Int = PRODUCT_IMAGE_API_BODY_MAX_BYTES
    ): Response {
        val root = baseUrl ?: throw ProductImageException("image_request_failed")
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
            throw ProductImageException("image_request_failed", response.status.value)
        }
        val text = try {
            readProductImageBodyBounded(
                response.bodyAsChannel(),
                maximumResponseBytes,
                overflowCode = "image_request_failed"
            ).toString(Charsets.UTF_8)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ProductImageException) {
            throw error
        } catch (_: Throwable) {
            throw ProductImageException("image_request_failed")
        }
        if (text.isBlank()) {
            throw ProductImageException("image_request_failed")
        }
        return try {
            json.decodeFromString<Response>(text)
        } catch (_: Throwable) {
            throw ProductImageException("image_request_failed")
        }
    }

    private fun validateBaseUrl(value: String): String? {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val uri = try {
            URI(trimmed)
        } catch (_: Throwable) {
            throw ProductImageException("image_request_failed")
        }
        if (uri.userInfo != null || uri.query != null || uri.fragment != null || uri.host.isNullOrBlank()) {
            throw ProductImageException("image_request_failed")
        }
        if (!isAllowedTransport(uri)) throw ProductImageException("image_request_failed")
        return trimmed
    }

    private fun validateSignedUrl(
        value: String,
        upload: Boolean,
        expectedReference: ProductImageReference
    ): String {
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
        val expectedObjectPath = buildString {
            append("shops/")
            append(expectedReference.shopId)
            append("/products/")
            append(expectedReference.productId)
            append("/primary/")
            append(expectedReference.versionId)
            append('/')
            append(expectedReference.variant.wireName)
            append(".jpg")
        }
        val rawPath = uri.rawPath.orEmpty()
        if (!PRODUCT_IMAGE_CACHE_SCOPE_PATTERN.matches(expectedReference.accountScope) ||
            !PRODUCT_IMAGE_UUID_PATTERN.matches(expectedReference.shopId) ||
            !PRODUCT_IMAGE_UUID_PATTERN.matches(expectedReference.productId) ||
            !PRODUCT_IMAGE_UUID_PATTERN.matches(expectedReference.versionId) ||
            !rawPath.startsWith(marker) ||
            !rawPath.removePrefix(marker).equals(expectedObjectPath, ignoreCase = true)
        ) {
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

internal suspend fun readProductImageBodyBounded(
    channel: ByteReadChannel,
    maximumBytes: Int,
    overflowCode: String = "image_download_invalid"
): ByteArray {
    require(maximumBytes > 0)
    val bytes = channel.readRemaining(maximumBytes.toLong() + 1L).readByteArray()
    if (bytes.size > maximumBytes) {
        channel.cancel()
        throw ProductImageException(overflowCode)
    }
    return bytes
}
