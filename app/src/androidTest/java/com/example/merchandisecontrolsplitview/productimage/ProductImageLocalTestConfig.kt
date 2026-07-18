package com.example.merchandisecontrolsplitview.productimage

import android.content.Context
import android.os.Process
import android.system.Os
import java.io.File
import java.net.URI
import org.json.JSONObject
import org.junit.Assert.assertEquals

internal data class ProductImageLocalTestConfig(
    val apiBase: String,
    val storageBase: String,
    val token: String,
    val accountId: String,
    val shopId: String,
    val productId: String,
    val versionId: String,
    val canWrite: Boolean
) {
    fun requireLoopbackReverseEndpoints() {
        requireLoopbackReverseEndpoint("apiBase", apiBase)
        requireLoopbackReverseEndpoint("storageBase", storageBase)
    }

    private fun requireLoopbackReverseEndpoint(label: String, value: String) {
        val uri = runCatching { URI(value) }.getOrNull()
            ?: throw AssertionError("$label must be a valid local URI")
        if (!uri.scheme.equals("http", ignoreCase = true) ||
            uri.host != "127.0.0.1" ||
            uri.port !in 1..65535 ||
            uri.userInfo != null ||
            uri.query != null ||
            uri.fragment != null
        ) {
            throw AssertionError(
                "$label must use an explicit 127.0.0.1 port with adb reverse"
            )
        }
    }
}

internal fun readPrivateProductImageConfig(
    context: Context,
    path: String,
    expectedFileName: String
): ProductImageLocalTestConfig {
    val configuredFile = File(path).absoluteFile
    val canonicalFile = configuredFile.canonicalFile
    val privateCacheRoot = context.cacheDir.canonicalFile
    if (canonicalFile.parentFile != privateCacheRoot ||
        canonicalFile.name != expectedFileName ||
        configuredFile != canonicalFile
    ) {
        throw AssertionError("config must be the canonical private app cache file")
    }
    val jsonText = try {
        if (!canonicalFile.isFile || canonicalFile.length() !in 1..MAX_CONFIG_BYTES) {
            throw AssertionError("config is missing or exceeds the bounded size")
        }
        val stat = Os.stat(canonicalFile.path)
        assertEquals("config must be owned by the app UID", Process.myUid(), stat.st_uid)
        assertEquals("config permissions must be 0600", MODE_0600, stat.st_mode and 0x1ff)
        canonicalFile.readText(Charsets.UTF_8)
    } finally {
        if (canonicalFile.exists() && !canonicalFile.delete()) {
            throw AssertionError("config could not be deleted after reading")
        }
    }
    val json = JSONObject(jsonText)
    if (json.keys().asSequence().toSet() != CONFIG_FIELDS) {
        throw AssertionError("config fields must match the bounded schema")
    }
    return ProductImageLocalTestConfig(
        apiBase = json.requiredString("apiBase"),
        storageBase = json.requiredString("storageBase"),
        token = json.requiredString("token"),
        accountId = json.requiredUuid("accountId"),
        shopId = json.requiredUuid("shopId"),
        productId = json.requiredUuid("productId"),
        versionId = json.requiredUuid("versionId"),
        canWrite = json.getBoolean("canWrite")
    ).also(ProductImageLocalTestConfig::requireLoopbackReverseEndpoints)
}

internal fun isProductImageOptInEnabled(value: String?): Boolean =
    value == "1" || value.equals("true", ignoreCase = true)

private fun JSONObject.requiredString(key: String): String =
    getString(key).takeIf { it.isNotBlank() }
        ?: throw AssertionError("config field $key must not be blank")

private fun JSONObject.requiredUuid(key: String): String =
    requiredString(key).takeIf(PRODUCT_IMAGE_UUID_PATTERN::matches)
        ?: throw AssertionError("config field $key must be a UUID")

private const val MAX_CONFIG_BYTES = 16L * 1024L
private const val MODE_0600 = 0x180
private val CONFIG_FIELDS = setOf(
    "apiBase",
    "storageBase",
    "token",
    "accountId",
    "shopId",
    "productId",
    "versionId",
    "canWrite"
)
