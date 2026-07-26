package com.example.merchandisecontrolsplitview.util

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal class ImportResourceLimitExceededException(
    val resource: String,
    val limitBytes: Long
) : IOException("Import resource limit exceeded: $resource (limit=$limitBytes bytes)")

internal object ImportResourcePolicy {
    const val MAX_SOURCE_BYTES: Long = 32L * 1024L * 1024L
    const val MAX_ARCHIVE_ENTRIES: Int = 2_048
    const val MAX_ARCHIVE_ENTRY_BYTES: Long = 128L * 1024L * 1024L
    const val MAX_ARCHIVE_EXPANDED_BYTES: Long = 256L * 1024L * 1024L

    fun validateSourceSize(sizeBytes: Long, maxBytes: Long = MAX_SOURCE_BYTES) {
        if (sizeBytes >= 0L && sizeBytes > maxBytes) {
            throw ImportResourceLimitExceededException("source", maxBytes)
        }
    }

    fun validateSourceSizeIfAvailable(
        contentResolver: ContentResolver,
        uri: Uri,
        maxBytes: Long = MAX_SOURCE_BYTES
    ) {
        val declaredSize = try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length
            }
        } catch (_: FileNotFoundException) {
            null
        }
        declaredSize?.let { validateSourceSize(it, maxBytes) }
    }

    fun readBytes(
        input: InputStream,
        maxBytes: Long = MAX_SOURCE_BYTES,
        resource: String = "source"
    ): ByteArray {
        val initialCapacity = minOf(maxBytes, DEFAULT_BUFFER_SIZE.toLong()).toInt()
        return ByteArrayOutputStream(initialCapacity).use { output ->
            copy(input, output, maxBytes, resource)
            output.toByteArray()
        }
    }

    fun copy(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long = MAX_SOURCE_BYTES,
        resource: String = "source"
    ): Long {
        require(maxBytes >= 0L) { "maxBytes must not be negative" }

        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue

            val updatedTotal = total + read.toLong()
            if (updatedTotal > maxBytes) {
                throw ImportResourceLimitExceededException(resource, maxBytes)
            }
            output.write(buffer, 0, read)
            total = updatedTotal
        }
        return total
    }

    class ArchiveBudget(
        private val maxEntries: Int = MAX_ARCHIVE_ENTRIES,
        private val maxEntryBytes: Long = MAX_ARCHIVE_ENTRY_BYTES,
        private val maxExpandedBytes: Long = MAX_ARCHIVE_EXPANDED_BYTES
    ) {
        private var entryCount = 0
        private var declaredExpandedBytes = 0L
        private var runtimeExpandedBytes = 0L

        fun readEntry(input: InputStream, declaredSizeBytes: Long = -1L): ByteArray {
            entryCount += 1
            if (entryCount > maxEntries) {
                throw ImportResourceLimitExceededException(
                    resource = "archive-entry-count",
                    limitBytes = maxEntries.toLong()
                )
            }

            if (declaredSizeBytes >= 0L) {
                if (declaredSizeBytes > maxEntryBytes) {
                    throw ImportResourceLimitExceededException("archive-entry", maxEntryBytes)
                }
                declaredExpandedBytes = checkedAdd(
                    current = declaredExpandedBytes,
                    delta = declaredSizeBytes,
                    maxBytes = maxExpandedBytes,
                    resource = "archive-expanded-metadata"
                )
            }

            val bytes = readBytes(
                input = input,
                maxBytes = maxEntryBytes,
                resource = "archive-entry"
            )
            runtimeExpandedBytes = checkedAdd(
                current = runtimeExpandedBytes,
                delta = bytes.size.toLong(),
                maxBytes = maxExpandedBytes,
                resource = "archive-expanded-runtime"
            )
            return bytes
        }

        private fun checkedAdd(
            current: Long,
            delta: Long,
            maxBytes: Long,
            resource: String
        ): Long {
            if (delta > maxBytes - current) {
                throw ImportResourceLimitExceededException(resource, maxBytes)
            }
            return current + delta
        }
    }
}
