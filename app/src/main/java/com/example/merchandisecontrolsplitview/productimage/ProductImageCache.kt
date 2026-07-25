package com.example.merchandisecontrolsplitview.productimage

import android.content.Context
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val PRODUCT_IMAGE_MEMORY_CACHE_MAX_BYTES = 8L * 1024L * 1024L
internal const val PRODUCT_IMAGE_DISK_CACHE_MAX_BYTES = 64L * 1024L * 1024L
internal const val PRODUCT_IMAGE_DISK_CACHE_MAX_ENTRIES = 256
internal const val PRODUCT_IMAGE_DISK_CACHE_MAX_FILES = PRODUCT_IMAGE_DISK_CACHE_MAX_ENTRIES * 2

private const val PRODUCT_IMAGE_CACHE_RECEIPT_VERSION = 1
private const val PRODUCT_IMAGE_CACHE_RECEIPT_MAX_BYTES = 4 * 1024
private const val PRODUCT_IMAGE_CACHE_SCAN_MULTIPLIER = 4
private const val PRODUCT_IMAGE_CACHE_SCAN_HEADROOM = 512

internal data class ProductImageCacheSnapshot(
    val memoryBytes: Long,
    val memoryEntries: Int,
    val diskBytes: Long,
    val diskEntries: Int,
    val diskFiles: Int = diskEntries * 2
)

@Serializable
private data class ProductImageCacheReceipt(
    val accountScope: String,
    val bytes: Int,
    val height: Int,
    val mimeType: String,
    val productId: String,
    val schemaVersion: Int,
    val sha256: String,
    val shopId: String,
    val variant: String,
    val versionId: String,
    val width: Int
) {
    fun metadata() = ProductImageMetadata(
        bytes = bytes,
        height = height,
        mimeType = mimeType,
        sha256 = sha256,
        width = width
    )
}

private data class ProductImageMemoryCacheEntry(
    val bytes: ByteArray,
    val receipt: ProductImageCacheReceipt
)

private data class ProductImageDiskCacheEntry(
    val jpeg: Path,
    val lastModified: Long,
    val receipt: Path,
    val reference: ProductImageReference,
    val totalBytes: Long
)

class ProductImageCache private constructor(
    private val root: File,
    private val memoryMaxBytes: Long,
    private val diskMaxBytes: Long,
    private val diskMaxEntries: Int,
    private val diskMaxFiles: Int,
    private val nowEpochMillis: () -> Long
) {
    constructor(context: Context) : this(
        root = File(context.noBackupFilesDir, "product-images/v1"),
        memoryMaxBytes = PRODUCT_IMAGE_MEMORY_CACHE_MAX_BYTES,
        diskMaxBytes = PRODUCT_IMAGE_DISK_CACHE_MAX_BYTES,
        diskMaxEntries = PRODUCT_IMAGE_DISK_CACHE_MAX_ENTRIES,
        diskMaxFiles = PRODUCT_IMAGE_DISK_CACHE_MAX_FILES,
        nowEpochMillis = System::currentTimeMillis
    )

    internal constructor(
        testRoot: File,
        testOnly: Unit = Unit,
        memoryMaxBytes: Long = PRODUCT_IMAGE_MEMORY_CACHE_MAX_BYTES,
        diskMaxBytes: Long = PRODUCT_IMAGE_DISK_CACHE_MAX_BYTES,
        diskMaxEntries: Int = PRODUCT_IMAGE_DISK_CACHE_MAX_ENTRIES,
        diskMaxFiles: Int = PRODUCT_IMAGE_DISK_CACHE_MAX_FILES,
        nowEpochMillis: () -> Long = System::currentTimeMillis
    ) : this(
        testRoot,
        memoryMaxBytes,
        diskMaxBytes,
        diskMaxEntries,
        diskMaxFiles,
        nowEpochMillis
    )

    private val rootPath = root.toPath().toAbsolutePath().normalize()
    private val receiptJson = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }
    private val memoryEntries = LinkedHashMap<String, ProductImageMemoryCacheEntry>(
        16,
        0.75f,
        true
    )
    private val diskEntries = LinkedHashMap<String, ProductImageDiskCacheEntry>(
        16,
        0.75f,
        true
    )
    private var memoryBytes = 0L
    private var diskBytes = 0L

    init {
        require(
            memoryMaxBytes > 0L &&
                diskMaxBytes > 0L &&
                diskMaxEntries > 0 &&
                diskMaxFiles > 0
        )
        ensureRootDirectory()
        rebuildDiskIndex()
        enforceDiskBudget()
    }

    fun accountScope(accountId: String): String {
        if (!PRODUCT_IMAGE_UUID_PATTERN.matches(accountId)) {
            throw ProductImageException("image_reference_invalid")
        }
        return sha256("product-image-account:$accountId".encodeToByteArray())
    }

    /**
     * A hit is accepted only while both the JPEG and its committed receipt are
     * regular no-follow files and the receipt still proves the exact bytes,
     * dimensions, MIME and scope key. Memory hits deliberately repeat the
     * receipt/payload validation instead of trusting process-local state.
     */
    @Synchronized
    fun read(reference: ProductImageReference): ByteArray? {
        val jpeg = fileFor(reference).toPath()
        val receiptPath = receiptFor(jpeg)
        val key = keyFor(jpeg)
        val receipt = readCommittedReceipt(reference, jpeg, receiptPath)
            ?: return invalidateEntry(key, jpeg, receiptPath)
        val diskBytes = readFileBoundedNoFollow(jpeg, reference.variant.maxBytes)
            ?: return invalidateEntry(key, jpeg, receiptPath)
        if (!isValidCachePayload(reference, diskBytes, receipt)) {
            return invalidateEntry(key, jpeg, receiptPath)
        }

        memoryEntries[key]?.let { cached ->
            if (cached.receipt == receipt &&
                cached.bytes.contentEquals(diskBytes) &&
                isValidCachePayload(reference, cached.bytes, receipt)
            ) {
                touchDiskEntry(key, jpeg, receiptPath, reference)
                return cached.bytes.copyOf()
            }
            removeMemoryEntry(key)
        }

        putMemoryEntry(key, diskBytes, receipt)
        touchDiskEntry(key, jpeg, receiptPath, reference)
        return diskBytes.copyOf()
    }

    /**
     * The receipt is the commit marker. The previous marker is removed before
     * replacing the JPEG and the new marker is atomically moved last. A crash
     * at any intermediate step therefore leaves an invalid pair that the next
     * hit/relaunch removes instead of presenting stale or unverified bytes.
     */
    internal fun write(reference: ProductImageReference, bytes: ByteArray) {
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        } ?: throw ProductImageException("image_download_invalid")
        val metadata = try {
            ProductImageMetadata(
                bytes = bytes.size,
                height = bitmap.height,
                mimeType = "image/jpeg",
                sha256 = sha256(bytes),
                width = bitmap.width
            )
        } finally {
            bitmap.recycle()
        }
        write(reference, bytes, metadata)
    }

    @Synchronized
    fun write(
        reference: ProductImageReference,
        bytes: ByteArray,
        metadata: ProductImageMetadata
    ) {
        val receipt = receipt(reference, metadata)
        if (!isValidCachePayload(reference, bytes, receipt)) {
            throw ProductImageException("image_download_invalid")
        }
        val jpeg = fileFor(reference).toPath()
        val receiptPath = receiptFor(jpeg)
        val parent = jpeg.parent ?: throw ProductImageException("image_reference_invalid")
        ensureSafeDirectory(parent)
        rejectSymbolicLeaf(jpeg)
        rejectSymbolicLeaf(receiptPath)

        val nonce = UUID.randomUUID().toString()
        val temporaryJpeg = parent.resolve(".${reference.variant.wireName}-$nonce.jpg.tmp")
        val temporaryReceipt = parent.resolve(".${reference.variant.wireName}-$nonce.receipt.tmp")
        try {
            writeFileSyncedNoFollow(temporaryJpeg, bytes)
            writeFileSyncedNoFollow(
                temporaryReceipt,
                receiptJson.encodeToString(receipt).encodeToByteArray()
            )
            if (!isValidCachePayload(
                    reference,
                    readFileBoundedNoFollow(temporaryJpeg, reference.variant.maxBytes)
                        ?: throw ProductImageException("image_request_failed"),
                    readReceiptFileNoFollow(temporaryReceipt)
                        ?: throw ProductImageException("image_request_failed")
                )
            ) {
                throw ProductImageException("image_request_failed")
            }

            deleteLeafNoFollow(receiptPath)
            atomicReplace(temporaryJpeg, jpeg)
            try {
                atomicReplace(temporaryReceipt, receiptPath)
            } catch (error: Exception) {
                deleteLeafNoFollow(jpeg)
                throw error
            }
        } catch (error: ProductImageException) {
            throw error
        } catch (_: Exception) {
            throw ProductImageException("image_request_failed")
        } finally {
            deleteLeafNoFollow(temporaryJpeg)
            deleteLeafNoFollow(temporaryReceipt)
        }

        val timestamp = nowEpochMillis()
        setLastModifiedNoFollow(jpeg, timestamp)
        setLastModifiedNoFollow(receiptPath, timestamp)
        val key = keyFor(jpeg)
        putMemoryEntry(key, bytes, receipt)
        replaceDiskEntry(key, jpeg, receiptPath, reference, timestamp)
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
        diskEntries.values
            .filter {
                it.reference.accountScope == accountScope &&
                    it.reference.shopId == shopId &&
                    it.reference.productId == productId &&
                    it.reference.versionId != keepVersionId
            }
            .map { keyFor(it.jpeg) }
            .forEach(::removeIndexedEntry)

        val productPath = productRoot(accountScope, shopId, productId).toPath()
        listChildrenNoFollow(productPath).forEach { versionPath ->
            if (versionPath.fileName.toString() != keepVersionId &&
                PRODUCT_IMAGE_UUID_PATTERN.matches(versionPath.fileName.toString())
            ) {
                deleteTreeNoFollow(versionPath)
            }
        }
        pruneParents(productPath)
    }

    @Synchronized
    fun purgeProduct(accountScope: String, shopId: String, productId: String) {
        validateScopeAndIds(accountScope, shopId, productId)
        purgeTree(productRoot(accountScope, shopId, productId).toPath())
    }

    @Synchronized
    fun purgeShop(accountScope: String, shopId: String) {
        validateScopeAndIds(accountScope, shopId)
        purgeTree(rootPath.resolve(accountScope).resolve(shopId))
    }

    @Synchronized
    fun purgeAccount(accountScope: String) {
        validateScopeAndIds(accountScope)
        purgeTree(rootPath.resolve(accountScope))
    }

    @Synchronized
    fun purgeAll() {
        memoryEntries.clear()
        memoryBytes = 0L
        diskEntries.clear()
        diskBytes = 0L
        listChildrenNoFollow(rootPath).forEach(::deleteTreeNoFollow)
    }

    @Synchronized
    fun trimMemory() {
        memoryEntries.clear()
        memoryBytes = 0L
    }

    @Synchronized
    internal fun snapshot(): ProductImageCacheSnapshot =
        ProductImageCacheSnapshot(
            memoryBytes = memoryBytes,
            memoryEntries = memoryEntries.size,
            diskBytes = diskBytes,
            diskEntries = diskEntries.size,
            diskFiles = diskEntries.size * 2
        )

    internal fun fileFor(reference: ProductImageReference): File {
        validateScopeAndIds(
            reference.accountScope,
            reference.shopId,
            reference.productId,
            reference.versionId
        )
        val path = productRoot(
            reference.accountScope,
            reference.shopId,
            reference.productId
        ).toPath()
            .resolve(reference.versionId)
            .resolve("${reference.variant.wireName}.jpg")
            .toAbsolutePath()
            .normalize()
        requireContained(path)
        return path.toFile()
    }

    internal fun receiptFileFor(reference: ProductImageReference): File =
        receiptFor(fileFor(reference).toPath()).toFile()

    private fun productRoot(accountScope: String, shopId: String, productId: String): File =
        rootPath.resolve(accountScope).resolve(shopId).resolve(productId).toFile()

    private fun validateScopeAndIds(accountScope: String, vararg ids: String) {
        if (!PRODUCT_IMAGE_CACHE_SCOPE_PATTERN.matches(accountScope) ||
            ids.any { !PRODUCT_IMAGE_UUID_PATTERN.matches(it) }
        ) {
            throw ProductImageException("image_reference_invalid")
        }
    }

    private fun receipt(
        reference: ProductImageReference,
        metadata: ProductImageMetadata
    ) = ProductImageCacheReceipt(
        accountScope = reference.accountScope,
        bytes = metadata.bytes,
        height = metadata.height,
        mimeType = metadata.mimeType,
        productId = reference.productId,
        schemaVersion = PRODUCT_IMAGE_CACHE_RECEIPT_VERSION,
        sha256 = metadata.sha256,
        shopId = reference.shopId,
        variant = reference.variant.wireName,
        versionId = reference.versionId,
        width = metadata.width
    )

    private fun receiptMatchesReference(
        receipt: ProductImageCacheReceipt,
        reference: ProductImageReference
    ): Boolean =
        receipt.schemaVersion == PRODUCT_IMAGE_CACHE_RECEIPT_VERSION &&
            receipt.accountScope == reference.accountScope &&
            receipt.shopId == reference.shopId &&
            receipt.productId == reference.productId &&
            receipt.versionId == reference.versionId &&
            receipt.variant == reference.variant.wireName &&
            receipt.mimeType == "image/jpeg" &&
            PRODUCT_IMAGE_SHA256_PATTERN.matches(receipt.sha256) &&
            receipt.bytes in 1..reference.variant.maxBytes &&
            receipt.width > 0 &&
            receipt.height > 0 &&
            maxOf(receipt.width, receipt.height) <= maximumSide(reference.variant)

    private fun isValidCachePayload(
        reference: ProductImageReference,
        bytes: ByteArray,
        receipt: ProductImageCacheReceipt
    ): Boolean {
        if (!receiptMatchesReference(receipt, reference) ||
            bytes.size != receipt.bytes ||
            sha256(bytes) != receipt.sha256 ||
            !isJpeg(bytes) ||
            jpegContainsForbiddenMetadata(bytes)
        ) {
            return false
        }
        val bounds = decodeProductImageBounds(bytes) ?: return false
        if (bounds.width != receipt.width ||
            bounds.height != receipt.height ||
            maxOf(bounds.width, bounds.height) > maximumSide(reference.variant)
        ) {
            return false
        }
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        } ?: return false
        return try {
            bitmap.width == receipt.width &&
                bitmap.height == receipt.height &&
                maxOf(bitmap.width, bitmap.height) <= maximumSide(reference.variant)
        } finally {
            bitmap.recycle()
        }
    }

    private fun maximumSide(variant: ProductImageVariant): Int = when (variant) {
        ProductImageVariant.MAIN -> PRODUCT_IMAGE_MAIN_MAX_SIDE
        ProductImageVariant.THUMB -> PRODUCT_IMAGE_THUMB_MAX_SIDE
    }

    private fun readCommittedReceipt(
        reference: ProductImageReference,
        jpeg: Path,
        receiptPath: Path
    ): ProductImageCacheReceipt? {
        if (!isRegularFileNoFollow(jpeg) || !isRegularFileNoFollow(receiptPath)) {
            return null
        }
        val receipt = readReceiptFileNoFollow(receiptPath) ?: return null
        if (!receiptMatchesReference(receipt, reference)) return null
        val length = fileSizeNoFollow(jpeg) ?: return null
        if (length != receipt.bytes.toLong()) return null
        return receipt
    }

    private fun readReceiptFileNoFollow(path: Path): ProductImageCacheReceipt? {
        val bytes = readFileBoundedNoFollow(path, PRODUCT_IMAGE_CACHE_RECEIPT_MAX_BYTES)
            ?: return null
        if (bytes.isEmpty()) return null
        return try {
            receiptJson.decodeFromString(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun putMemoryEntry(
        key: String,
        bytes: ByteArray,
        receipt: ProductImageCacheReceipt
    ) {
        removeMemoryEntry(key)
        val owned = ProductImageMemoryCacheEntry(bytes.copyOf(), receipt)
        memoryEntries[key] = owned
        memoryBytes += owned.bytes.size
        val iterator = memoryEntries.entries.iterator()
        while (memoryBytes > memoryMaxBytes && iterator.hasNext()) {
            val eldest = iterator.next()
            memoryBytes -= eldest.value.bytes.size
            iterator.remove()
        }
    }

    private fun removeMemoryEntry(key: String) {
        memoryEntries.remove(key)?.let { memoryBytes -= it.bytes.size }
    }

    private fun removeMemoryPrefix(prefix: String) {
        val iterator = memoryEntries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.startsWith(prefix)) {
                memoryBytes -= entry.value.bytes.size
                iterator.remove()
            }
        }
    }

    private fun replaceDiskEntry(
        key: String,
        jpeg: Path,
        receiptPath: Path,
        reference: ProductImageReference,
        lastModified: Long
    ) {
        diskEntries.remove(key)?.let { diskBytes -= it.totalBytes }
        val totalBytes = (fileSizeNoFollow(jpeg) ?: 0L) +
            (fileSizeNoFollow(receiptPath) ?: 0L)
        val entry = ProductImageDiskCacheEntry(
            jpeg = jpeg,
            lastModified = lastModified,
            receipt = receiptPath,
            reference = reference,
            totalBytes = totalBytes
        )
        diskEntries[key] = entry
        diskBytes += totalBytes
    }

    private fun touchDiskEntry(
        key: String,
        jpeg: Path,
        receiptPath: Path,
        reference: ProductImageReference
    ) {
        val timestamp = nowEpochMillis()
        setLastModifiedNoFollow(jpeg, timestamp)
        setLastModifiedNoFollow(receiptPath, timestamp)
        replaceDiskEntry(key, jpeg, receiptPath, reference, timestamp)
        enforceDiskBudget()
    }

    private fun enforceDiskBudget() {
        val iterator = diskEntries.entries.iterator()
        while (
            (diskBytes > diskMaxBytes ||
                diskEntries.size > diskMaxEntries ||
                diskEntries.size * 2 > diskMaxFiles) &&
            iterator.hasNext()
        ) {
            val entry = iterator.next()
            diskBytes -= entry.value.totalBytes
            removeMemoryEntry(entry.key)
            deleteLeafNoFollow(entry.value.receipt)
            deleteLeafNoFollow(entry.value.jpeg)
            iterator.remove()
            pruneParents(entry.value.jpeg.parent)
        }
    }

    private fun removeIndexedEntry(key: String) {
        val entry = diskEntries.remove(key) ?: return
        diskBytes -= entry.totalBytes
        removeMemoryEntry(key)
        deleteLeafNoFollow(entry.receipt)
        deleteLeafNoFollow(entry.jpeg)
        pruneParents(entry.jpeg.parent)
    }

    private fun invalidateEntry(
        key: String,
        jpeg: Path,
        receiptPath: Path
    ): ByteArray? {
        diskEntries.remove(key)?.let { diskBytes -= it.totalBytes }
        removeMemoryEntry(key)
        deleteLeafNoFollow(receiptPath)
        deleteLeafNoFollow(jpeg)
        pruneParents(jpeg.parent)
        return null
    }

    private fun purgeTree(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        requireContained(normalized)
        val prefix = keyFor(normalized) + File.separator
        removeMemoryPrefix(prefix)
        diskEntries.values
            .filter { it.jpeg.startsWith(normalized) }
            .map { keyFor(it.jpeg) }
            .forEach { key ->
                diskEntries.remove(key)?.let { diskBytes -= it.totalBytes }
            }
        deleteTreeNoFollow(normalized)
        pruneParents(normalized.parent)
    }

    /**
     * A single bounded scan is paid on construction/relaunch. Writes update
     * the access-ordered index directly, so normal maintenance is O(evicted
     * entries) and never repeats a full tree walk/sort per write.
     */
    private fun rebuildDiskIndex() {
        diskEntries.clear()
        diskBytes = 0L
        val paths = scanCachePathsBounded()
        val jpegPaths = paths.filter { path ->
            isRegularFileNoFollow(path) &&
                path.fileName.toString().endsWith(".jpg", ignoreCase = true)
        }
        val receiptPaths = paths.filter { path ->
            isRegularFileNoFollow(path) &&
                path.fileName.toString().endsWith(".receipt.json", ignoreCase = true)
        }.toMutableSet()
        val entries = mutableListOf<ProductImageDiskCacheEntry>()

        for (jpeg in jpegPaths) {
            val reference = referenceFor(jpeg)
            val receiptPath = receiptFor(jpeg)
            receiptPaths.remove(receiptPath)
            if (reference == null) {
                deleteLeafNoFollow(jpeg)
                deleteLeafNoFollow(receiptPath)
                continue
            }
            val receipt = readCommittedReceipt(reference, jpeg, receiptPath)
            if (receipt == null) {
                deleteLeafNoFollow(receiptPath)
                deleteLeafNoFollow(jpeg)
                continue
            }
            val bytes = readFileBoundedNoFollow(jpeg, reference.variant.maxBytes)
            if (bytes == null || !isValidCachePayload(reference, bytes, receipt)) {
                deleteLeafNoFollow(receiptPath)
                deleteLeafNoFollow(jpeg)
                continue
            }
            val entry = ProductImageDiskCacheEntry(
                jpeg = jpeg,
                lastModified = minOf(lastModifiedNoFollow(jpeg), lastModifiedNoFollow(receiptPath)),
                receipt = receiptPath,
                reference = reference,
                totalBytes = requireNotNull(fileSizeNoFollow(jpeg)) +
                    requireNotNull(fileSizeNoFollow(receiptPath))
            )
            entries += entry
        }
        receiptPaths.forEach(::deleteLeafNoFollow)
        paths.filter { path ->
            val name = path.fileName.toString()
            (isRegularFileNoFollow(path) || Files.isSymbolicLink(path)) &&
                (name.endsWith(".tmp") || name.endsWith(".jpg.tmp") ||
                    name.endsWith(".receipt.tmp"))
        }.forEach(::deleteLeafNoFollow)

        entries.sortedBy(ProductImageDiskCacheEntry::lastModified).forEach { entry ->
            diskEntries[keyFor(entry.jpeg)] = entry
            diskBytes += entry.totalBytes
        }
        pruneEmptyDirectories(paths)
    }

    private fun scanCachePathsBounded(): List<Path> {
        val maximumPaths = (diskMaxFiles * PRODUCT_IMAGE_CACHE_SCAN_MULTIPLIER +
            PRODUCT_IMAGE_CACHE_SCAN_HEADROOM).coerceAtLeast(1_024)
        val discovered = mutableListOf<Path>()
        val pending = ArrayDeque<Path>()
        pending.add(rootPath)
        var visited = 0
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (++visited > maximumPaths) {
                throw ProductImageException("image_request_failed")
            }
            if (current != rootPath) discovered.add(current)
            val attributes = attributesNoFollow(current) ?: continue
            if (!attributes.isDirectory || attributes.isSymbolicLink) continue
            Files.newDirectoryStream(current).use { stream ->
                stream.forEach { child ->
                    val normalized = child.toAbsolutePath().normalize()
                    requireContained(normalized)
                    pending.addLast(normalized)
                }
            }
        }
        return discovered
    }

    private fun referenceFor(jpeg: Path): ProductImageReference? {
        val relative = rootPath.relativize(jpeg)
        if (relative.nameCount != 5) return null
        val accountScope = relative.getName(0).toString()
        val shopId = relative.getName(1).toString()
        val productId = relative.getName(2).toString()
        val versionId = relative.getName(3).toString()
        val variant = when (relative.getName(4).toString()) {
            "main.jpg" -> ProductImageVariant.MAIN
            "thumb.jpg" -> ProductImageVariant.THUMB
            else -> return null
        }
        if (!PRODUCT_IMAGE_CACHE_SCOPE_PATTERN.matches(accountScope) ||
            listOf(shopId, productId, versionId).any {
                !PRODUCT_IMAGE_UUID_PATTERN.matches(it)
            }
        ) {
            return null
        }
        return ProductImageReference(accountScope, shopId, productId, versionId, variant)
    }

    private fun ensureRootDirectory() {
        if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(rootPath)
        }
        val attributes = attributesNoFollow(rootPath)
            ?: throw ProductImageException("image_request_failed")
        if (!attributes.isDirectory || attributes.isSymbolicLink) {
            throw ProductImageException("image_request_failed")
        }
    }

    private fun ensureSafeDirectory(directory: Path) {
        val normalized = directory.toAbsolutePath().normalize()
        requireContained(normalized)
        ensureRootDirectory()
        var current = rootPath
        for (component in rootPath.relativize(normalized)) {
            current = current.resolve(component)
            val attributes = attributesNoFollow(current)
            if (attributes == null) {
                Files.createDirectory(current)
            } else if (!attributes.isDirectory || attributes.isSymbolicLink) {
                throw ProductImageException("image_reference_invalid")
            }
        }
    }

    private fun rejectSymbolicLeaf(path: Path) {
        if (Files.isSymbolicLink(path)) {
            throw ProductImageException("image_reference_invalid")
        }
        val attributes = attributesNoFollow(path)
        if (attributes != null && !attributes.isRegularFile) {
            throw ProductImageException("image_reference_invalid")
        }
    }

    private fun requireContained(path: Path) {
        if (path != rootPath && !path.startsWith(rootPath)) {
            throw ProductImageException("image_reference_invalid")
        }
    }

    private fun readFileBoundedNoFollow(path: Path, maximumBytes: Int): ByteArray? {
        if (!isRegularFileNoFollow(path)) return null
        return try {
            Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                val limit = maximumBytes + 1
                val buffer = ByteArray(minOf(8 * 1_024, limit))
                val output = ByteArrayOutputStream(minOf(8 * 1_024, limit))
                var remaining = limit
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) break
                    if (count == 0) {
                        val single = input.read()
                        if (single < 0) break
                        output.write(single)
                        remaining -= 1
                    } else {
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                }
                val bytes = output.toByteArray()
                bytes.takeIf { value -> value.size <= maximumBytes }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeFileSyncedNoFollow(path: Path, bytes: ByteArray) {
        requireContained(path)
        FileChannel.open(
            path,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        if (Files.isSymbolicLink(path)) {
            deleteLeafNoFollow(path)
            throw ProductImageException("image_reference_invalid")
        }
    }

    private fun atomicReplace(source: Path, target: Path) {
        requireContained(source)
        requireContained(target)
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun deleteLeafNoFollow(path: Path) {
        requireContained(path.toAbsolutePath().normalize())
        try {
            Files.deleteIfExists(path)
        } catch (_: Exception) {
            // Cache cleanup is best effort. A later validated hit stays
            // fail-closed because no operation follows a symbolic leaf.
        }
    }

    private fun deleteTreeNoFollow(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        requireContained(normalized)
        val attributes = attributesNoFollow(normalized) ?: return
        if (!attributes.isDirectory || attributes.isSymbolicLink) {
            deleteLeafNoFollow(normalized)
            return
        }
        listChildrenNoFollow(normalized).forEach(::deleteTreeNoFollow)
        deleteLeafNoFollow(normalized)
    }

    private fun listChildrenNoFollow(path: Path): List<Path> {
        val normalized = path.toAbsolutePath().normalize()
        requireContained(normalized)
        val attributes = attributesNoFollow(normalized) ?: return emptyList()
        if (!attributes.isDirectory || attributes.isSymbolicLink) return emptyList()
        return try {
            Files.newDirectoryStream(normalized).use { stream ->
                stream.map { child ->
                    child.toAbsolutePath().normalize().also(::requireContained)
                }.toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun pruneParents(start: Path?) {
        var current = start?.toAbsolutePath()?.normalize()
        while (current != null && current != rootPath && current.startsWith(rootPath)) {
            val attributes = attributesNoFollow(current)
            if (attributes == null) {
                current = current.parent
                continue
            }
            if (!attributes.isDirectory || attributes.isSymbolicLink) return
            val empty = try {
                Files.newDirectoryStream(current).use { !it.iterator().hasNext() }
            } catch (_: Exception) {
                false
            }
            if (!empty) return
            deleteLeafNoFollow(current)
            current = current.parent
        }
    }

    private fun pruneEmptyDirectories(paths: List<Path>) {
        paths.asSequence()
            .filter { attributesNoFollow(it)?.isDirectory == true }
            .sortedByDescending { it.nameCount }
            .forEach(::pruneParents)
    }

    private fun attributesNoFollow(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS
        )
    } catch (_: Exception) {
        null
    }

    private fun isRegularFileNoFollow(path: Path): Boolean {
        val attributes = attributesNoFollow(path) ?: return false
        return attributes.isRegularFile && !attributes.isSymbolicLink
    }

    private fun fileSizeNoFollow(path: Path): Long? =
        attributesNoFollow(path)
            ?.takeIf { it.isRegularFile && !it.isSymbolicLink }
            ?.size()

    private fun lastModifiedNoFollow(path: Path): Long =
        attributesNoFollow(path)?.lastModifiedTime()?.toMillis() ?: 0L

    private fun setLastModifiedNoFollow(path: Path, timestamp: Long) {
        if (!isRegularFileNoFollow(path)) return
        try {
            Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(timestamp))
        } catch (_: Exception) {
            // Access order is also maintained in memory; timestamp persistence
            // is best effort and never weakens receipt validation.
        }
    }

    private fun receiptFor(jpeg: Path): Path {
        val name = jpeg.fileName.toString()
        require(name.endsWith(".jpg")) { "cache JPEG name required" }
        return jpeg.resolveSibling(name.removeSuffix(".jpg") + ".receipt.json")
    }

    private fun keyFor(path: Path): String = path.toAbsolutePath().normalize().toString()
}
