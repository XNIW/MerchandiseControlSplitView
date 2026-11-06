package com.example.merchandisecontrolsplitview.data

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A local body proof for an unchanged Room store. Only the original validator may
 * mint it. The proof never authorizes an account, selects a shop or moves a cursor.
 * Any write that changes the durable database bytes makes the proof ineligible.
 */
internal class OrdinaryLocalStoreQualification(
    private val databaseFile: File,
    private val witnessDirectory: File
) {
    private data class FileIdentity(val device: Long, val inode: Long)

    @Volatile private var openedMain: FileIdentity? = null

    private val wal = File(databaseFile.path + "-wal")
    private val shm = File(databaseFile.path + "-shm")
    private val rollbackJournal = File(databaseFile.path + "-journal")
    private val witness = File(witnessDirectory, "${databaseFile.name}.ordinary-local-store-v1")

    fun bindOpenedStore(path: String?) {
        openedMain = fileOperation {
            if (path != databaseFile.absolutePath) return@fileOperation null
            identity(regularFile(databaseFile) ?: return@fileOperation null)
        }
    }

    /**
     * Call only inside the owning Room writer transaction, which pins the
     * SQLite snapshot and excludes other SQLite writers. A WAL index is
     * reconstructed by SQLite at open and is not a source of durable rows.
     * Treat a newly created zero-length WAL exactly like an absent WAL.
     */
    suspend fun fingerprint(): String? {
        val work = currentCoroutineContext()
        return fileOperation {
            val expectedMain = openedMain ?: return@fileOperation null
            val mainBefore = regularFile(databaseFile) ?: return@fileOperation null
            if (identity(mainBefore) != expectedMain || mainBefore.st_size == 0L ||
                lstatOrNull(rollbackJournal) != null) return@fileOperation null
            // Reject an unexpected file type, but allow SQLite to change read
            // marks or reconstruct this non-durable index.
            regularFile(shm)
            val walBefore = regularFile(wal)?.takeUnless { it.st_size == 0L }
            val mainHash = hashFile(databaseFile, mainBefore) { work.ensureActive() }
            val walHash = walBefore?.let { hashFile(wal, it) { work.ensureActive() } }
            val mainAfter = regularFile(databaseFile)
            val walAfter = regularFile(wal)?.takeUnless { it.st_size == 0L }
            if (!sameFile(mainBefore, mainAfter) || !sameFile(walBefore, walAfter) ||
                lstatOrNull(rollbackJournal) != null) return@fileOperation null
            task126OwnerHash(
                "${expectedMain.device}:${expectedMain.inode}:${mainBefore.st_size}:$mainHash:" +
                    "${walBefore?.st_size ?: 0L}:${walHash.orEmpty()}"
            )
        }
    }

    fun matches(scopeKey: String, fingerprint: String): Boolean = fileOperation {
        val stat = regularFile(witness) ?: return@fileOperation false
        if (stat.st_size !in 1L..MAX_WITNESS_BYTES) return@fileOperation false
        val bytes = ByteArrayOutputStream(stat.st_size.toInt())
        readFile(witness, stat, ByteArray(stat.st_size.toInt())) { buffer, count ->
            bytes.write(buffer, 0, count)
        }
        DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use { input ->
            input.readInt() == VERSION &&
                input.readUTF() == scopeKey &&
                input.readUTF() == fingerprint &&
                input.readUTF() == checksum(scopeKey, fingerprint) &&
                input.read() == -1 &&
                sameFile(stat, regularFile(witness))
        }
    } == true

    fun record(scopeKey: String, fingerprint: String): Boolean = fileOperation {
        if (!witnessDirectory.isDirectory && !witnessDirectory.mkdir()) return@fileOperation false
        // The parent entry is part of durability on the first publication.
        syncDirectory(witnessDirectory.parentFile ?: return@fileOperation false)
        syncDirectory(witnessDirectory)
        val pending = File.createTempFile("ordinary-proof-", ".partial", witnessDirectory)
        try {
            val output = FileOutputStream(pending)
            DataOutputStream(output).use {
                it.writeInt(VERSION)
                it.writeUTF(scopeKey)
                it.writeUTF(fingerprint)
                it.writeUTF(checksum(scopeKey, fingerprint))
                it.flush()
                output.fd.sync()
            }
            Os.rename(pending.path, witness.path)
            syncDirectory(witnessDirectory)
            true
        } finally {
            if (pending.exists()) pending.delete()
        }
    } == true

    private fun hashFile(file: File, stat: StructStat, checkCancellation: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        readFile(file, stat, ByteArray(256 * 1024)) { buffer, count ->
            checkCancellation()
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readFile(file: File, stat: StructStat, buffer: ByteArray,
        consume: (ByteArray, Int) -> Unit
    ) {
        withRegularFile(file, stat) { fd ->
            var remaining = stat.st_size
            while (remaining > 0) {
                val count = Os.read(fd, buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count <= 0) throw IOException("qualification_file_changed")
                consume(buffer, count)
                remaining -= count
            }
            if (Os.read(fd, ByteArray(1), 0, 1) != 0 || !sameFile(stat, Os.fstat(fd))) {
                throw IOException("qualification_file_changed")
            }
        }
    }

    private inline fun <T> withRegularFile(file: File, expected: StructStat,
        read: (FileDescriptor) -> T
    ): T {
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or
            OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        try {
            val actual = Os.fstat(fd)
            if (!OsConstants.S_ISREG(actual.st_mode) || !sameFile(expected, actual)) {
                throw IOException("qualification_file_changed")
            }
            return read(fd)
        } finally {
            Os.close(fd)
        }
    }

    private fun regularFile(file: File): StructStat? = lstatOrNull(file)?.also { stat ->
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 1L) {
            throw IOException("qualification_file_type")
        }
    }

    private fun lstatOrNull(file: File): StructStat? = try {
        Os.lstat(file.path)
    } catch (error: ErrnoException) {
        if (error.errno != OsConstants.ENOENT) throw error
        null
    }

    private fun identity(stat: StructStat) = FileIdentity(stat.st_dev, stat.st_ino)

    private fun sameFile(left: StructStat?, right: StructStat?): Boolean {
        if (left == null || right == null) return left == null && right == null
        return identity(left) == identity(right) && left.st_mode == right.st_mode &&
            left.st_nlink == right.st_nlink && left.st_size == right.st_size &&
            left.st_mtim == right.st_mtim && left.st_ctim == right.st_ctim
    }

    private fun syncDirectory(directory: File) {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or
            OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw IOException("qualification_directory_type")
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }

    private fun checksum(scopeKey: String, fingerprint: String): String =
        task126OwnerHash("$VERSION:$scopeKey:$fingerprint")

    private inline fun <T> fileOperation(block: () -> T): T? = try {
        block()
    } catch (_: ErrnoException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private companion object {
        const val VERSION = 1
        const val MAX_WITNESS_BYTES = 256L
    }
}
