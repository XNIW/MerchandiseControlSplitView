package com.example.merchandisecontrolsplitview

import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

/** Test-only immutable payload + atomic ready-directory publication. No replacement or recovery.
 * The mkdir is the logical publication point; writing a regular file is not claimed to be atomic.
 * A claim/partial payload without Ready is unpublished and is never repaired. Consumption latches
 * deliberately use a different presence-only protocol and must not call this helper. */
internal object B6PublishedFile {
    const val PROTOCOL = "EXCLUSIVE_PAYLOAD_READY_DIRECTORY_V1"
    private const val FILE_MODE = 384 // 0600
    private const val DIR_MODE = 448 // 0700; inherited setgid is harmless with no group permissions.
    internal enum class Point { PAYLOAD_CLAIMED, PAYLOAD_WRITTEN, FILE_SYNCED, PARENT_SYNCED, READY_CREATED, COMMITTED }

    private fun stat(path: String): StructStat? = try { Os.lstat(path) } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) null else throw error
    }
    private fun sameObject(a: StructStat, b: StructStat) = a.st_dev == b.st_dev && a.st_ino == b.st_ino &&
        a.st_uid == b.st_uid && a.st_gid == b.st_gid && a.st_mode == b.st_mode && a.st_nlink == b.st_nlink
    private fun sameDirectory(a: StructStat, b: StructStat) = a.st_dev == b.st_dev && a.st_ino == b.st_ino &&
        a.st_uid == b.st_uid && a.st_gid == b.st_gid && a.st_mode == b.st_mode
    private fun sameContent(a: StructStat, b: StructStat) = sameObject(a,b) && a.st_size == b.st_size &&
        a.st_mtime == b.st_mtime && a.st_ctime == b.st_ctime
    private fun regular(value: StructStat, maximum: Int) {
        check(OsConstants.S_ISREG(value.st_mode) && value.st_uid == Process.myUid() && value.st_nlink == 1L &&
            (value.st_mode and 511) == FILE_MODE && (value.st_mode and 3584) == 0 && value.st_size in 0..maximum.toLong()) { "B6_PAYLOAD_SHAPE" }
    }
    private fun readyDirectory(value: StructStat) {
        check(OsConstants.S_ISDIR(value.st_mode) && value.st_uid == Process.myUid() &&
            (value.st_mode and 511) == DIR_MODE && (value.st_mode and 3584) in setOf(0,1024)) { "B6_READY_SHAPE" }
    }
    private class Parent(file: File): Closeable {
        val parent = requireNotNull(file.absoluteFile.parentFile)
        val name = file.name.also { check(it.matches(Regex("[A-Za-z0-9_.-]{1,180}")) && it != "." && it != "..") }
        private val before: StructStat
        val fd: ParcelFileDescriptor
        val leaf: String
        val ready: String
        init {
            check(file.path == file.absolutePath && parent.canonicalPath == parent.path) { "B6_PARENT_ALIAS" }
            before = requireNotNull(stat(parent.path))
            check(OsConstants.S_ISDIR(before.st_mode) && before.st_uid == Process.myUid()) { "B6_PARENT_SHAPE" }
            val original = Os.open(parent.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW,0)
            try {
                check(sameDirectory(before,Os.fstat(original))) { "B6_PARENT_CHANGED" }
                fd = ParcelFileDescriptor.dup(original)
            } finally { Os.close(original) }
            // Public descriptor API anchors all leaf operations to the held directory, including a rename race.
            val held = "/proc/self/fd/${fd.fd}"
            leaf = "$held/$name"; ready = "$held/$name.ready"
            try { unchanged() } catch (error: Throwable) { fd.close(); throw error }
        }
        fun unchanged() {
            check(parent.canonicalPath == parent.path && stat(parent.path)?.let { sameDirectory(before,it) } == true &&
                sameDirectory(before,Os.fstat(fd.fileDescriptor))) { "B6_PARENT_CHANGED" }
        }
        fun marker(): StructStat? {
            unchanged()
            return stat(ready)?.also {
                readyDirectory(it)
                check(File(parent,"$name.ready").canonicalPath == File(parent,"$name.ready").absolutePath) { "B6_READY_ALIAS" }
            }
        }
        fun sync() { Os.fsync(fd.fileDescriptor) }
        override fun close() = fd.close()
    }

    /** Checks only publication presence/shape. Consumers still call read/requireRead before using bytes. */
    fun isPublished(file: File): Boolean = Parent(file).use { parent -> parent.marker() != null }

    private fun bounded(stream: FileInputStream, maximum: Int): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            check(bytes.size().toLong()+count <= maximum) { "B6_PAYLOAD_OVERFLOW" }
            bytes.write(buffer,0,count)
        }
        return bytes.toByteArray()
    }
    /** Missing Ready means NOT_PUBLISHED, including an existing partial payload. Invalid Ready fails closed. */
    fun read(file: File, maximum: Int): ByteArray? = Parent(file).use { parent ->
        check(maximum > 0)
        val marker = parent.marker() ?: return@use null
        val initial = requireNotNull(stat(parent.leaf)) { "B6_READY_WITHOUT_PAYLOAD" }
        regular(initial,maximum)
        val descriptor = Os.open(parent.leaf,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW,0)
        try { FileInputStream(descriptor).use { stream ->
            val opened = Os.fstat(descriptor); check(sameContent(initial,opened)) { "B6_PAYLOAD_CHANGED" }; regular(opened,maximum)
            val raw = bounded(stream,maximum); check(raw.size.toLong() == opened.st_size && raw.size <= maximum) { "B6_PAYLOAD_LENGTH" }
            // A second read on the same held descriptor catches changes during the first read.
            Os.lseek(descriptor,0L,OsConstants.SEEK_SET)
            check(bounded(stream,maximum).contentEquals(raw)) { "B6_PAYLOAD_UNSTABLE" }
            Os.fsync(descriptor); parent.sync()
            check(sameContent(opened,Os.fstat(descriptor)) && stat(parent.leaf)?.let { sameContent(opened,it) } == true &&
                parent.marker()?.let { sameObject(marker,it) } == true) { "B6_PUBLICATION_CHANGED" }
            parent.unchanged()
            raw
        } } finally { Os.close(descriptor) } // Android streams borrow caller-supplied descriptors.
    }
    fun requireRead(file: File, maximum: Int): ByteArray = requireNotNull(read(file,maximum)) { "B6_NOT_PUBLISHED" }

    /** Owns one O_EXCL claim. Appends are allowed only until seal; no reopened file or late append. */
    class Pending internal constructor(file: File, private val maximum: Int,
        private val point: (Point)->Unit): Closeable {
        private val parent = Parent(file)
        private val output: FileOutputStream
        private var ownedDescriptor: FileDescriptor? = null
        private var closed = false
        private val identity: StructStat
        private var length = 0
        private var terminal = false
        var sealed = false; private set
        init {
            var opened: FileOutputStream? = null
            try {
                check(maximum > 0 && parent.marker() == null) { "B6_READY_PREEXISTS" }
                val descriptor = Os.open(parent.leaf,OsConstants.O_WRONLY or OsConstants.O_CREAT or
                    OsConstants.O_EXCL or OsConstants.O_NOFOLLOW,FILE_MODE)
                ownedDescriptor = descriptor
                output = FileOutputStream(descriptor); opened = output
                identity = Os.fstat(descriptor); regular(identity,maximum)
                parent.unchanged(); point(Point.PAYLOAD_CLAIMED)
            } catch (error: Throwable) {
                try { opened?.close() } finally {
                    try { ownedDescriptor?.let { Os.close(it) } } finally { ownedDescriptor = null; parent.close() }
                }
                throw error
            }
        }
        fun append(raw: ByteArray) {
            check(!terminal && !sealed && length.toLong()+raw.size <= maximum) { "B6_APPEND_AFTER_SEAL_OR_OVERFLOW" }
            parent.unchanged(); check(parent.marker() == null) { "B6_READY_PREEXISTS" }
            check(stat(parent.leaf)?.let { sameObject(identity,it) && it.st_size == length.toLong() } == true) { "B6_CLAIM_CHANGED" }
            output.write(raw); output.flush(); length += raw.size
            point(Point.PAYLOAD_WRITTEN)
            output.fd.sync(); point(Point.FILE_SYNCED)
            parent.unchanged()
        }
        fun seal() {
            check(!terminal && !sealed) { "B6_SEAL_REPEATED" }
            terminal = true // Every seal attempt consumes this publisher, including uncertainty.
            parent.unchanged(); check(parent.marker() == null) { "B6_READY_PREEXISTS" }
            output.fd.sync(); point(Point.FILE_SYNCED)
            val written = Os.fstat(output.fd); regular(written,maximum)
            check(sameObject(identity,written) && written.st_size == length.toLong() &&
                stat(parent.leaf)?.let { sameContent(written,it) } == true) { "B6_CLAIM_CHANGED" }
            parent.sync(); point(Point.PARENT_SYNCED); parent.unchanged()
            Os.mkdir(parent.ready,DIR_MODE)
            // Any failure after mkdir is uncertain: never retry, remove or recreate either member.
            try {
                point(Point.READY_CREATED)
                readyDirectory(requireNotNull(parent.marker()))
                parent.sync(); parent.unchanged(); point(Point.COMMITTED)
                sealed = true
            } catch (error: Throwable) { throw IllegalStateException("B6_PUBLICATION_UNCERTAIN_NO_RETRY",error) }
        }
        override fun close() {
            if(closed) return
            closed = true
            try { output.close() } finally {
                try { ownedDescriptor?.let { Os.close(it) } } finally { ownedDescriptor = null; parent.close() }
            }
        }
    }
    fun pending(file: File, maximum: Int, point: (Point)->Unit = {}): Pending = Pending(file,maximum,point)
    fun publish(file: File, raw: ByteArray, point: (Point)->Unit = {}) {
        pending(file,raw.size.coerceAtLeast(1),point).use { it.append(raw); it.seal() }
    }
}
