package com.example.merchandisecontrolsplitview

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual app-UID filesystem tests only: no app launch, UI action, business call or auth/session access.
 * Fault hooks model process interruption boundaries; they do not claim to simulate power loss.
 * Each fixture owns a new target-cache UUID and removes only its own test files after assertions.
 * A foreign UID cannot be manufactured without privilege; no wrong-UID PASS is implied here. */
@RunWith(AndroidJUnit4::class)
class B6PublishedFileTest {
    private val raw = "{\"synthetic_publication\":true}\n".toByteArray(Charsets.UTF_8)
    private class Fault : RuntimeException("SYNTHETIC_FILESYSTEM_BOUNDARY")
    private fun rejected(block: () -> Unit): Throwable {
        try { block() } catch (error: Exception) { return error }
        throw AssertionError("Expected fail closed")
    }
    private class Fixture : AutoCloseable {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile,
            "b6-publication-test-${UUID.randomUUID()}").also { check(it.mkdir()) }
        fun file(name: String = "payload.json") = File(directory,name)
        fun child(name: String) = File(directory,name).also { Os.mkdir(it.path,448) }
        fun marker(file: File) = File(requireNotNull(file.parentFile),file.name+".ready")
        fun regular(file: File, bytes: ByteArray, mode: Int = 384) {
            val descriptor = Os.open(file.path,OsConstants.O_WRONLY or OsConstants.O_CREAT or
                OsConstants.O_EXCL or OsConstants.O_NOFOLLOW,mode)
            try { FileOutputStream(descriptor).use { it.write(bytes); it.flush(); it.fd.sync() } }
            finally { Os.close(descriptor) }
        }
        override fun close() {
            fun removeOwned(file: File) {
                val stat = try { Os.lstat(file.path) } catch (error: ErrnoException) {
                    if (error.errno == OsConstants.ENOENT) return else throw error
                }
                check(stat.st_uid == Process.myUid()) { "TEST_CLEANUP_FOREIGN_UID" }
                // lstat avoids traversing a symlink, including the deliberately installed parent alias.
                if (OsConstants.S_ISDIR(stat.st_mode)) file.listFiles()?.forEach(::removeOwned)
                check(file.delete()) { "TEST_CLEANUP_FAILED" }
            }
            removeOwned(directory)
        }
    }

    @Test fun completePublicationUsesReadyDirectoryAndRetainsExactBytes() = Fixture().use { f ->
        val file = f.file(); val points = mutableListOf<B6PublishedFile.Point>()
        B6PublishedFile.publish(file,raw) { points.add(it) }
        assertTrue(B6PublishedFile.isPublished(file))
        assertTrue(OsConstants.S_ISDIR(Os.lstat(f.marker(file).path).st_mode))
        assertArrayEquals(raw,B6PublishedFile.requireRead(file,65536))
        assertTrue(points.indexOf(B6PublishedFile.Point.FILE_SYNCED) < points.indexOf(B6PublishedFile.Point.PARENT_SYNCED))
        assertTrue(points.indexOf(B6PublishedFile.Point.PARENT_SYNCED) < points.indexOf(B6PublishedFile.Point.READY_CREATED))
        assertEquals(B6PublishedFile.Point.COMMITTED,points.last())
    }

    @Test fun missingReadyMakesAbsentAndPartialPayloadsUnpublished() = Fixture().use { f ->
        val absent = f.file("absent.json")
        assertFalse(B6PublishedFile.isPublished(absent)); assertNull(B6PublishedFile.read(absent,65536))
        val partial = f.file("partial.json"); f.regular(partial,raw.copyOf(7))
        assertFalse(B6PublishedFile.isPublished(partial)); assertNull(B6PublishedFile.read(partial,65536))
        rejected { B6PublishedFile.publish(partial,raw) }
        assertArrayEquals(raw.copyOf(7),partial.readBytes())
        assertFalse(f.marker(partial).exists())
    }

    @Test fun interruptionsBeforeReadyRetainUnpublishedClaimsAndRefuseRetry() = Fixture().use { f ->
        listOf(B6PublishedFile.Point.PAYLOAD_CLAIMED,B6PublishedFile.Point.PAYLOAD_WRITTEN,
            B6PublishedFile.Point.FILE_SYNCED,B6PublishedFile.Point.PARENT_SYNCED).forEachIndexed { index,stop ->
            val file = f.file("interrupted-$index.json")
            assertTrue(rejected { B6PublishedFile.publish(file,raw) { if(it == stop) throw Fault() } } is Fault)
            val retained = file.readBytes()
            assertNull(B6PublishedFile.read(file,65536)); assertFalse(f.marker(file).exists())
            rejected { B6PublishedFile.publish(file,raw) }
            assertArrayEquals(retained,file.readBytes())
            assertFalse(f.marker(file).exists())
        }
    }

    @Test fun failureAfterReadyIsUncertainAndCannotPublishAgain() = Fixture().use { f ->
        val file = f.file()
        val failure = rejected { B6PublishedFile.publish(file,raw) {
            if(it == B6PublishedFile.Point.READY_CREATED) throw Fault()
        } }
        assertEquals("B6_PUBLICATION_UNCERTAIN_NO_RETRY",failure.message)
        assertTrue(f.marker(file).isDirectory); assertArrayEquals(raw,file.readBytes())
        rejected { B6PublishedFile.publish(file,"replacement".toByteArray()) }
        assertArrayEquals(raw,file.readBytes())
        // The publisher remains uncertain; a consumer independently fsyncs the complete publication before use.
        assertArrayEquals(raw,B6PublishedFile.requireRead(file,65536))
    }

    @Test fun concurrentPublishersHaveOneClaimWinnerAndCannotMixPayloads() = Fixture().use { f ->
        val file = f.file(); val begin = CountDownLatch(1); val done = CountDownLatch(2)
        val payloads = listOf(raw,"{\"other_conforming_writer\":true}\n".toByteArray())
        val winners = Collections.synchronizedList(mutableListOf<Int>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = payloads.mapIndexed { index,bytes -> Thread {
            try {
                check(begin.await(10,TimeUnit.SECONDS))
                B6PublishedFile.publish(file,bytes); winners.add(index)
            } catch (error: Throwable) { failures.add(error) } finally { done.countDown() }
        }.apply { name = "b6-publication-test-$index"; start() } }
        try {
            begin.countDown(); assertTrue("Both publishers terminate",done.await(10,TimeUnit.SECONDS))
            threads.forEach { it.join(1000); assertFalse(it.isAlive) }
            assertEquals(1,winners.size); assertEquals(1,failures.size)
            val loser = failures.single()
            assertTrue(loser is ErrnoException && loser.errno == OsConstants.EEXIST ||
                loser is IllegalStateException && loser.message == "B6_READY_PREEXISTS")
            assertArrayEquals(payloads[winners.single()],B6PublishedFile.requireRead(file,65536))
        } finally {
            begin.countDown(); threads.forEach { if(it.isAlive) it.interrupt() }; threads.forEach { it.join(1000) }
        }
    }

    @Test fun losingWriterCannotCreateReadyForAnotherPartialClaim() = Fixture().use { f ->
        val file = f.file()
        B6PublishedFile.pending(file,65536).use { winner ->
            winner.append(raw.copyOf(7))
            rejected { B6PublishedFile.publish(file,raw) }
            assertNull(B6PublishedFile.read(file,65536)); assertFalse(f.marker(file).exists())
            winner.append(raw.copyOfRange(7,raw.size)); winner.seal()
        }
        assertArrayEquals(raw,B6PublishedFile.requireRead(file,65536))
    }

    @Test fun staleReadyDirectoryStopsBeforePayloadClaim() = Fixture().use { f ->
        val file = f.file(); Os.mkdir(f.marker(file).path,448)
        assertEquals("B6_READY_PREEXISTS",rejected { B6PublishedFile.publish(file,raw) }.message)
        assertFalse(file.exists())
        assertEquals("B6_READY_WITHOUT_PAYLOAD",rejected { B6PublishedFile.requireRead(file,65536) }.message)
    }

    @Test fun staleReadyRegularFileStopsBeforePayloadClaimAndRead() = Fixture().use { f ->
        val file = f.file(); f.regular(f.marker(file),"stale".toByteArray())
        rejected { B6PublishedFile.publish(file,raw) }; assertFalse(file.exists())
        rejected { B6PublishedFile.requireRead(file,65536) }
        assertArrayEquals("stale".toByteArray(),f.marker(file).readBytes())
    }

    @Test fun readySymlinkAndDanglingSymlinkAreRejectedWithoutClaim() = Fixture().use { f ->
        val real = f.child("real-ready")
        listOf(real.path,File(f.directory,"never-created").path).forEachIndexed { index,target ->
            val file = f.file("symlink-$index.json"); Os.symlink(target,f.marker(file).path)
            rejected { B6PublishedFile.publish(file,raw) }; assertFalse(file.exists())
            rejected { B6PublishedFile.requireRead(file,65536) }
            assertTrue(OsConstants.S_ISLNK(Os.lstat(f.marker(file).path).st_mode))
        }
    }

    @Test fun readyWithoutPayloadAndSymlinkPayloadFailClosed() = Fixture().use { f ->
        val missing = f.file("missing.json"); Os.mkdir(f.marker(missing).path,448)
        rejected { B6PublishedFile.requireRead(missing,65536) }
        val target = f.file("owned-target.json"); f.regular(target,raw)
        val alias = f.file("alias.json"); Os.symlink(target.path,alias.path); Os.mkdir(f.marker(alias).path,448)
        rejected { B6PublishedFile.requireRead(alias,65536) }
        assertArrayEquals(raw,target.readBytes())
    }

    @Test fun zeroPermissionPayloadOrReadyAreRejected(): Unit = Fixture().use { f ->
        val payload = f.file("broad-payload.json"); f.regular(payload,raw,0); Os.mkdir(f.marker(payload).path,448)
        rejected { B6PublishedFile.requireRead(payload,65536) }
        val marker = f.file("broad-marker.json"); f.regular(marker,raw); Os.mkdir(f.marker(marker).path,0)
        rejected { B6PublishedFile.requireRead(marker,65536) }
    }

    @Test fun canonicalParentAliasIsRejectedForPublisherAndConsumer() = Fixture().use { f ->
        val real = f.child("real"); val alias = File(f.directory,"parent-alias"); Os.symlink(real.path,alias.path)
        val throughAlias = File(alias,"payload.json")
        rejected { B6PublishedFile.publish(throughAlias,raw) }
        rejected { B6PublishedFile.read(throughAlias,65536) }
        assertFalse(File(real,"payload.json").exists()); assertFalse(File(real,"payload.json.ready").exists())
    }

    @Test fun renamedParentAndAliasCannotRedirectWrittenPayload() = Fixture().use { f ->
        val original = f.child("original"); val held = File(f.directory,"held"); val redirect = f.child("redirect")
        val file = File(original,"payload.json")
        val failure = rejected { B6PublishedFile.publish(file,raw) { point ->
            if(point == B6PublishedFile.Point.PAYLOAD_WRITTEN) {
                Os.rename(original.path,held.path); Os.symlink(redirect.path,original.path)
            }
        } }
        assertEquals("B6_PARENT_CHANGED",failure.message)
        assertArrayEquals(raw,File(held,"payload.json").readBytes())
        assertFalse(File(held,"payload.json.ready").exists())
        assertFalse(File(redirect,"payload.json").exists()); assertFalse(File(redirect,"payload.json.ready").exists())
        rejected { B6PublishedFile.read(file,65536) }
        assertNull(B6PublishedFile.read(File(held,"payload.json"),65536))
    }

    @Test fun parentChangeAfterReadyIsUncertainWithoutRedirectedPublication() = Fixture().use { f ->
        val original = f.child("original"); val held = File(f.directory,"held"); val redirect = f.child("redirect")
        val failure = rejected { B6PublishedFile.publish(File(original,"payload.json"),raw) { point ->
            if(point == B6PublishedFile.Point.READY_CREATED) {
                Os.rename(original.path,held.path); Os.symlink(redirect.path,original.path)
            }
        } }
        assertEquals("B6_PUBLICATION_UNCERTAIN_NO_RETRY",failure.message)
        assertTrue(File(held,"payload.json.ready").isDirectory)
        assertArrayEquals(raw,File(held,"payload.json").readBytes())
        assertFalse(File(redirect,"payload.json").exists()); assertFalse(File(redirect,"payload.json.ready").exists())
    }

    @Test fun queryLogSealsOnceAndRejectsEveryLaterAppendOrSeal() = Fixture().use { f ->
        val file = f.file("queries.jsonl"); val first = "{\"sequence\":0}\n".toByteArray(); val second = "{\"sequence\":1}\n".toByteArray()
        B6PublishedFile.pending(file,65536).use { log ->
            log.append(first); assertNull(B6PublishedFile.read(file,65536))
            log.append(second); assertNull(B6PublishedFile.read(file,65536))
            log.seal(); assertTrue(log.sealed)
            assertArrayEquals(first+second,B6PublishedFile.requireRead(file,65536))
            rejected { log.append("late".toByteArray()) }; rejected { log.seal() }
            assertArrayEquals(first+second,B6PublishedFile.requireRead(file,65536))
        }
    }

    @Test fun everyOwnedDescriptorClosesOnSuccessAndFaultBoundaries() = Fixture().use { f ->
        fun ownedDescriptors(): List<String> = requireNotNull(File("/proc/self/fd").listFiles()).mapNotNull { entry ->
            val target = try { Os.readlink(entry.path) } catch (error: ErrnoException) {
                if(error.errno == OsConstants.ENOENT) null else throw error
            }
            target?.takeIf { it == f.directory.path || it.startsWith(f.directory.path+"/") }
        }
        val file = f.file("repeated.json"); B6PublishedFile.publish(file,raw)
        repeat(32) { assertArrayEquals(raw,B6PublishedFile.requireRead(file,65536)) }
        rejected { B6PublishedFile.requireRead(file,raw.size-1) }
        B6PublishedFile.Point.values().forEachIndexed { index,stop ->
            rejected { B6PublishedFile.publish(f.file("fd-fault-$index.json"),raw) { if(it == stop) throw Fault() } }
        }
        assertEquals("Only descriptors targeting this test's owned UUID are counted",emptyList<String>(),ownedDescriptors())
    }

    @Test fun boundedReaderAndAppenderRejectOverflowWithoutReplacement() = Fixture().use { f ->
        val published = f.file("published.json"); B6PublishedFile.publish(published,raw)
        rejected { B6PublishedFile.requireRead(published,raw.size-1) }
        assertArrayEquals(raw,B6PublishedFile.requireRead(published,65536))
        val partial = f.file("bounded.json")
        B6PublishedFile.pending(partial,7).use { value ->
            value.append(raw.copyOf(7)); rejected { value.append(byteArrayOf(1)) }
            assertNull(B6PublishedFile.read(partial,65536)); assertFalse(f.marker(partial).exists())
        }
        assertArrayEquals(raw.copyOf(7),partial.readBytes())
    }
}
