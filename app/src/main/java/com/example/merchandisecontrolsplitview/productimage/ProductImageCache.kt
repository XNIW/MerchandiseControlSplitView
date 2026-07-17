package com.example.merchandisecontrolsplitview.productimage

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class ProductImageCache private constructor(private val root: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "product-images/v1"))

    internal constructor(testRoot: File, testOnly: Unit = Unit) : this(testRoot)

    fun accountScope(accountId: String): String {
        if (!PRODUCT_IMAGE_UUID_PATTERN.matches(accountId)) {
            throw ProductImageException("image_account_scope_invalid")
        }
        return sha256("product-image-account:$accountId".encodeToByteArray())
    }

    fun read(reference: ProductImageReference): ByteArray? {
        val file = fileFor(reference)
        if (!file.isFile || file.length() !in 1..reference.variant.maxBytes.toLong()) return null
        val bytes = try {
            file.readBytes()
        } catch (_: Throwable) {
            return null
        }
        return bytes.takeIf {
            it.size <= reference.variant.maxBytes && isJpeg(it) && !jpegContainsApp1(it)
        }
    }

    fun write(reference: ProductImageReference, bytes: ByteArray) {
        validateBytes(reference.variant, bytes)
        val target = fileFor(reference)
        val parent = target.parentFile ?: throw ProductImageException("image_cache_path_invalid")
        if (!parent.exists() && !parent.mkdirs()) {
            throw ProductImageException("image_cache_write_failed")
        }
        val temporary = File(parent, ".${reference.variant.wireName}-${UUID.randomUUID()}.tmp")
        try {
            temporary.writeBytes(bytes)
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Throwable) {
                if (target.exists() && !target.delete()) {
                    throw ProductImageException("image_cache_write_failed")
                }
                if (!temporary.renameTo(target)) {
                    throw ProductImageException("image_cache_write_failed")
                }
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun purgeOtherVersions(
        accountScope: String,
        shopId: String,
        productId: String,
        keepVersionId: String
    ) {
        validateScopeAndIds(accountScope, shopId, productId, keepVersionId)
        val productRoot = productRoot(accountScope, shopId, productId)
        productRoot.listFiles()?.forEach { versionDirectory ->
            if (versionDirectory.name != keepVersionId && PRODUCT_IMAGE_UUID_PATTERN.matches(versionDirectory.name)) {
                versionDirectory.deleteRecursively()
            }
        }
    }

    fun purgeProduct(accountScope: String, shopId: String, productId: String) {
        validateScopeAndIds(accountScope, shopId, productId)
        productRoot(accountScope, shopId, productId).deleteRecursively()
    }

    internal fun fileFor(reference: ProductImageReference): File {
        validateScopeAndIds(
            reference.accountScope,
            reference.shopId,
            reference.productId,
            reference.versionId
        )
        val file = File(
            productRoot(reference.accountScope, reference.shopId, reference.productId),
            "${reference.versionId}/${reference.variant.wireName}.jpg"
        )
        val canonicalRoot = root.canonicalFile
        val canonicalFile = file.canonicalFile
        if (!canonicalFile.path.startsWith(canonicalRoot.path + File.separator)) {
            throw ProductImageException("image_cache_path_invalid")
        }
        return canonicalFile
    }

    private fun productRoot(accountScope: String, shopId: String, productId: String): File =
        File(root, "$accountScope/$shopId/$productId")

    private fun validateScopeAndIds(accountScope: String, vararg ids: String) {
        if (!PRODUCT_IMAGE_CACHE_SCOPE_PATTERN.matches(accountScope) ||
            ids.any { !PRODUCT_IMAGE_UUID_PATTERN.matches(it) }
        ) {
            throw ProductImageException("image_cache_reference_invalid")
        }
    }

    private fun validateBytes(variant: ProductImageVariant, bytes: ByteArray) {
        if (bytes.isEmpty() ||
            bytes.size > variant.maxBytes ||
            !isJpeg(bytes) ||
            jpegContainsApp1(bytes)
        ) {
            throw ProductImageException("image_cache_bytes_invalid")
        }
    }
}
