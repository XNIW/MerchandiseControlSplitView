package com.example.merchandisecontrolsplitview.productimage

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.LinkedHashMap
import java.util.UUID

internal const val PRODUCT_IMAGE_MEMORY_CACHE_MAX_BYTES = 8L * 1024L * 1024L
internal const val PRODUCT_IMAGE_DISK_CACHE_MAX_BYTES = 64L * 1024L * 1024L

internal data class ProductImageCacheSnapshot(
    val memoryBytes: Long,
    val memoryEntries: Int,
    val diskBytes: Long,
    val diskEntries: Int
)

class ProductImageCache private constructor(
    private val root: File,
    private val memoryMaxBytes: Long,
    private val diskMaxBytes: Long,
    private val nowEpochMillis: () -> Long
) {
    constructor(context: Context) : this(
        root = File(context.noBackupFilesDir, "product-images/v1"),
        memoryMaxBytes = PRODUCT_IMAGE_MEMORY_CACHE_MAX_BYTES,
        diskMaxBytes = PRODUCT_IMAGE_DISK_CACHE_MAX_BYTES,
        nowEpochMillis = System::currentTimeMillis
    )

    internal constructor(
        testRoot: File,
        testOnly: Unit = Unit,
        memoryMaxBytes: Long = PRODUCT_IMAGE_MEMORY_CACHE_MAX_BYTES,
        diskMaxBytes: Long = PRODUCT_IMAGE_DISK_CACHE_MAX_BYTES,
        nowEpochMillis: () -> Long = System::currentTimeMillis
    ) : this(testRoot, memoryMaxBytes, diskMaxBytes, nowEpochMillis)

    private val memoryEntries = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var memoryBytes = 0L

    init {
        require(memoryMaxBytes > 0L && diskMaxBytes > 0L)
        cleanupTemporaryFiles()
        enforceDiskBudget()
    }

    fun accountScope(accountId: String): String {
        if (!PRODUCT_IMAGE_UUID_PATTERN.matches(accountId)) {
            throw ProductImageException("image_account_scope_invalid")
        }
        return sha256("product-image-account:$accountId".encodeToByteArray())
    }

    @Synchronized
    fun read(reference: ProductImageReference): ByteArray? {
        val file = fileFor(reference)
        val key = file.path
        memoryEntries[key]?.let { return it }
        if (!file.isFile || file.length() !in 1..reference.variant.maxBytes.toLong()) {
            removeMemoryEntry(key)
            if (file.exists()) file.delete()
            return null
        }
        val bytes = try {
            file.readBytes()
        } catch (_: Throwable) {
            return null
        }
        if (!isValidDecodedJpeg(reference.variant, bytes)) {
            removeMemoryEntry(key)
            file.delete()
            pruneEmptyDirectories()
            return null
        }
        file.setLastModified(nowEpochMillis())
        putMemoryEntry(key, bytes)
        return bytes
    }

    @Synchronized
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
        target.setLastModified(nowEpochMillis())
        putMemoryEntry(target.path, bytes)
        enforceDiskBudget()
    }

    @Synchronized
    fun purgeOtherVersions(
        accountScope: String,
        shopId: String,
        productId: String,
        keepVersionId: String
    ) {
        validateScopeAndIds(accountScope, shopId, productId, keepVersionId)
        val productRoot = productRoot(accountScope, shopId, productId)
        val productPrefix = productRoot.canonicalPath + File.separator
        val keptPrefix = File(productRoot, keepVersionId).canonicalPath + File.separator
        val memoryIterator = memoryEntries.entries.iterator()
        while (memoryIterator.hasNext()) {
            val entry = memoryIterator.next()
            if (entry.key.startsWith(productPrefix) && !entry.key.startsWith(keptPrefix)) {
                memoryBytes -= entry.value.size
                memoryIterator.remove()
            }
        }
        productRoot.listFiles()?.forEach { versionDirectory ->
            if (versionDirectory.name != keepVersionId && PRODUCT_IMAGE_UUID_PATTERN.matches(versionDirectory.name)) {
                versionDirectory.deleteRecursively()
            }
        }
        pruneEmptyDirectories()
    }

    @Synchronized
    fun purgeProduct(accountScope: String, shopId: String, productId: String) {
        validateScopeAndIds(accountScope, shopId, productId)
        val productRoot = productRoot(accountScope, shopId, productId).canonicalFile
        removeMemoryPrefix(productRoot.path + File.separator)
        productRoot.deleteRecursively()
        pruneEmptyDirectories()
    }

    @Synchronized
    fun purgeShop(accountScope: String, shopId: String) {
        validateScopeAndIds(accountScope, shopId)
        val shopRoot = File(root, "$accountScope/$shopId").canonicalFile
        removeMemoryPrefix(shopRoot.path + File.separator)
        shopRoot.deleteRecursively()
        pruneEmptyDirectories()
    }

    @Synchronized
    fun purgeAccount(accountScope: String) {
        validateScopeAndIds(accountScope)
        val accountRoot = File(root, accountScope).canonicalFile
        removeMemoryPrefix(accountRoot.path + File.separator)
        accountRoot.deleteRecursively()
        pruneEmptyDirectories()
    }

    @Synchronized
    fun trimMemory() {
        memoryEntries.clear()
        memoryBytes = 0L
    }

    @Synchronized
    internal fun snapshot(): ProductImageCacheSnapshot {
        val files = cacheFiles()
        return ProductImageCacheSnapshot(
            memoryBytes = memoryBytes,
            memoryEntries = memoryEntries.size,
            diskBytes = files.sumOf(File::length),
            diskEntries = files.size
        )
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
            !isValidDecodedJpeg(variant, bytes)
        ) {
            throw ProductImageException("image_cache_bytes_invalid")
        }
    }

    private fun isValidDecodedJpeg(variant: ProductImageVariant, bytes: ByteArray): Boolean {
        if (!isJpeg(bytes) || jpegContainsApp1(bytes)) return false
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) {
            null
        } ?: return false
        return try {
            val maxSide = when (variant) {
                ProductImageVariant.MAIN -> PRODUCT_IMAGE_MAIN_MAX_SIDE
                ProductImageVariant.THUMB -> PRODUCT_IMAGE_THUMB_MAX_SIDE
            }
            bitmap.width > 0 && bitmap.height > 0 && maxOf(bitmap.width, bitmap.height) <= maxSide
        } finally {
            bitmap.recycle()
        }
    }

    private fun putMemoryEntry(key: String, bytes: ByteArray) {
        removeMemoryEntry(key)
        memoryEntries[key] = bytes
        memoryBytes += bytes.size
        val iterator = memoryEntries.entries.iterator()
        while (memoryBytes > memoryMaxBytes && iterator.hasNext()) {
            val eldest = iterator.next()
            memoryBytes -= eldest.value.size
            iterator.remove()
        }
    }

    private fun removeMemoryEntry(key: String) {
        memoryEntries.remove(key)?.let { memoryBytes -= it.size }
    }

    private fun removeMemoryPrefix(prefix: String) {
        val iterator = memoryEntries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.startsWith(prefix)) {
                memoryBytes -= entry.value.size
                iterator.remove()
            }
        }
    }

    private fun cleanupTemporaryFiles() {
        if (!root.isDirectory) return
        root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".tmp") }
            .forEach(File::delete)
    }

    private fun enforceDiskBudget() {
        cleanupTemporaryFiles()
        val files = cacheFiles().sortedBy(File::lastModified)
        var totalBytes = files.sumOf(File::length)
        for (file in files) {
            if (totalBytes <= diskMaxBytes) break
            val length = file.length()
            if (file.delete()) totalBytes -= length
        }
        pruneEmptyDirectories()
    }

    private fun cacheFiles(): List<File> =
        if (!root.isDirectory) {
            emptyList()
        } else {
            root.walkTopDown()
                .filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
                .toList()
        }

    private fun pruneEmptyDirectories() {
        if (!root.isDirectory) return
        root.walkBottomUp()
            .filter { it.isDirectory && it != root && it.list()?.isEmpty() == true }
            .forEach(File::delete)
    }
}
