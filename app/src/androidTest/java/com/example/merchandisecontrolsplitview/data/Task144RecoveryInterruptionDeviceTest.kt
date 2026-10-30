package com.example.merchandisecontrolsplitview.data

import android.os.Process
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native Room/coordinator proof, synthetic read RPCs. Each prepare is intentionally interrupted
 * by the reviewed host protocol; it is not a passing JUnit test. No UI/scheduler/live acceptance.
 * Boundary names describe existing seams, not a claim about a final filesystem fsync.
 */
@RunWith(AndroidJUnit4::class)
class Task144RecoveryInterruptionDeviceTest {
    @Test fun prepareAtAcceptedPrefix(): Unit = runBlocking { prepare(RecoveryInterruptionBoundary.ACCEPTED_PREFIX) }
    @Test fun verifyAcceptedPrefixAfterProcessDeath(): Unit = runBlocking { withTimeout(90_000) { verify(RecoveryInterruptionBoundary.ACCEPTED_PREFIX) } }
    @Test fun prepareAtReadyJournal(): Unit = runBlocking { prepare(RecoveryInterruptionBoundary.READY) }
    @Test fun verifyReadyJournalAfterProcessDeath(): Unit = runBlocking { withTimeout(90_000) { verify(RecoveryInterruptionBoundary.READY) } }
    @Test fun prepareAfterActivationCommit(): Unit = runBlocking { prepare(RecoveryInterruptionBoundary.POST_COMMIT) }
    @Test fun verifyActivationCommitAfterProcessDeath(): Unit = runBlocking { withTimeout(90_000) { verify(RecoveryInterruptionBoundary.POST_COMMIT) } }

    private suspend fun prepare(boundary: RecoveryInterruptionBoundary) {
        val c = RecoveryInterruptionContract
        val context = interruptionOwnedContext(boundary.wire, fresh = true)
        val db = interruptionOpenDb(context)
        try {
            interruptionSeed(db)
            val remote = InterruptionRemote(File(context.root, "rpc-ledger.json"), fresh = true)
            val block: () -> Unit = {
                val marker = runBlocking {
                    assertBefore(db, boundary)
                    val stage = interruptionStage(context, db, boundary)
                    val journal = requireNotNull(db.syncRecoveryJournalDao().get())
                    val calls = remote.ledger().calls
                    assertTrue(calls.all { it.pid == Process.myPid() })
                    assertEquals(if (boundary == RecoveryInterruptionBoundary.ACCEPTED_PREFIX) 2 else 7, calls.count { it.kind == "page" })
                    assertEquals(if (boundary == RecoveryInterruptionBoundary.ACCEPTED_PREFIX) 1 else 2, calls.count { it.kind == "checkpoint" })
                    assertEquals(if (boundary == RecoveryInterruptionBoundary.ACCEPTED_PREFIX) 0 else 1, calls.count { it.kind == "marker" })
                    InterruptionMarker(c.PROTOCOL, context.runId, boundary.wire, Process.myPid(),
                        requireNotNull(journal.runId), journal.phase, requireNotNull(journal.stagingDatabaseName),
                        requireNotNull(journal.checkpointADigest), journal.checkpointBDigest, stage,
                        interruptionSha(remote.file.readBytes()))
                }
                interruptionDurableWrite(File(context.root, "prepared.json"), c.json.encodeToString(marker).encodeToByteArray())
                // Bounded observation barrier; AssertionError bypasses coordinator's Exception retry handler.
                CountDownLatch(1).await(90, TimeUnit.SECONDS)
                throw AssertionError("Host did not stop the qualified process within the observation window")
            }
            when (boundary) {
                RecoveryInterruptionBoundary.ACCEPTED_PREFIX -> remote.beforeSecondSupplierResponse = block
                RecoveryInterruptionBoundary.READY -> ShopSyncRecoveryTestHooks.afterReadyJournalPersisted = block
                RecoveryInterruptionBoundary.POST_COMMIT -> ShopSyncRecoveryTestHooks.afterActivationCommitted = block
            }
            val result = interruptionCoordinator(context, db, remote).recover(c.OWNER, c.shop, c.scope)
            throw AssertionError("Expected qualified interruption boundary was not reached: $result")
        } finally {
            ShopSyncRecoveryTestHooks.reset()
            db.close()
            // No reset/delete: failed journal, stage, marker and ledger remain owned evidence.
        }
    }

    private suspend fun verify(boundary: RecoveryInterruptionBoundary) {
        val c = RecoveryInterruptionContract
        val context = interruptionOwnedContext(boundary.wire, fresh = false)
        val preparedBytes = File(context.root, "prepared.json").readBytes()
        val marker = c.json.decodeFromString<InterruptionMarker>(preparedBytes.decodeToString())
        assertEquals(c.PROTOCOL, marker.protocol)
        assertEquals(context.runId, marker.runId)
        assertEquals(boundary.wire, marker.scenario)
        assertNotEquals("Verifier must reopen in another process", marker.preparePid, Process.myPid())
        val remote = InterruptionRemote(File(context.root, "rpc-ledger.json"), fresh = false)
        assertEquals(marker.ledgerSha256, interruptionSha(remote.file.readBytes()))
        val beforeCalls = remote.ledger().calls
        assertTrue(beforeCalls.all { it.pid == marker.preparePid })
        val db = interruptionOpenDb(context)
        try {
            assertBefore(db, boundary)
            val journal = requireNotNull(db.syncRecoveryJournalDao().get())
            assertEquals(marker.generation, journal.runId)
            assertEquals(marker.phase, journal.phase)
            assertEquals(marker.stagingName, journal.stagingDatabaseName)
            assertEquals(marker.checkpointADigest, journal.checkpointADigest)
            assertEquals(marker.checkpointBDigest, journal.checkpointBDigest)
            assertEquals(marker.stage, interruptionStage(context, db, boundary))
            val result = interruptionCoordinator(context, db, remote).recover(c.OWNER, c.shop, c.scope)
            assertTrue(result.toString(), result is ShopSyncRecoveryResult.Activated)
            assertActive(db, marker.generation)
            assertNull(db.syncRecoveryJournalDao().get())
            val allCalls = remote.ledger().calls
            assertEquals(beforeCalls, allCalls.take(beforeCalls.size))
            val calls = allCalls.drop(beforeCalls.size)
            assertTrue(calls.all { it.pid == Process.myPid() })
            val pages = calls.filter { it.kind == "page" }
            val checkpointCalls = calls.count { it.kind == "checkpoint" }
            val markerCalls = calls.count { it.kind == "marker" }
            assertEquals(if (boundary == RecoveryInterruptionBoundary.ACCEPTED_PREFIX) 6 else 0, pages.size)
            assertEquals(if (boundary == RecoveryInterruptionBoundary.POST_COMMIT) 0 else 1, checkpointCalls)
            assertEquals(if (boundary == RecoveryInterruptionBoundary.POST_COMMIT) 1 else 2, markerCalls)
            val supplierCalls = allCalls.filter { it.kind == "page" && it.domain == ShopSyncRowDomain.SUPPLIERS.wireValue }
            val first = supplierCalls.filter { it.afterId == null }
            val second = supplierCalls.filter { it.afterId == c.FIRST }
            assertEquals("Accepted first supplier page must never be redownloaded", 1, first.size)
            assertEquals(if (boundary == RecoveryInterruptionBoundary.ACCEPTED_PREFIX) 2 else 1, second.size)
            if (boundary == RecoveryInterruptionBoundary.ACCEPTED_PREFIX) {
                assertEquals("Resume must enter the same second page with identical fences/limit", second[0].copy(pid = Process.myPid()), second[1])
                assertEquals(c.FIRST, pages.first().afterId)
                assertEquals(ShopSyncRowDomain.SUPPLIERS.wireValue, pages.first().domain)
            }
            for (domain in ShopSyncRowDomain.entries.filter { it != ShopSyncRowDomain.SUPPLIERS }) {
                assertEquals("Completed domains must not be redownloaded", 1, allCalls.count { it.kind == "page" && it.domain == domain.wireValue })
            }
            val verified = InterruptionVerified(c.PROTOCOL, context.runId, boundary.wire, marker.preparePid, Process.myPid(),
                marker.generation, interruptionSha(preparedBytes), pages.size, checkpointCalls, markerCalls,
                allCalls.count { it.kind == "page" }, first.size, second.size, listOf(c.FIRST, c.SECOND), interruptionSha(remote.file.readBytes()))
            interruptionDurableWrite(File(context.root, "verified.json"), c.json.encodeToString(verified).encodeToByteArray())
        } finally {
            ShopSyncRecoveryTestHooks.reset()
            db.close()
            // The coordinator's successful cleanup is tested; the fixture itself never deletes evidence.
        }
    }

    private suspend fun assertBefore(db: AppDatabase, boundary: RecoveryInterruptionBoundary) {
        val c = RecoveryInterruptionContract
        val journal = requireNotNull(db.syncRecoveryJournalDao().get())
        assertEquals(boundary.phase, journal.phase)
        assertEquals(c.scope.ownerHash, journal.ownerHash)
        assertEquals(c.scope.storeId, journal.storeScope)
        assertEquals(c.SHOP, journal.shopId)
        assertEquals(c.DEVICE, journal.deviceId)
        if (boundary == RecoveryInterruptionBoundary.POST_COMMIT) {
            assertActive(db, requireNotNull(journal.runId))
        } else {
            assertNotNull(db.productDao().findByBarcode(c.OLD_BARCODE))
            assertEquals(1, db.productDao().count())
            assertEquals(1, db.syncEventOutboxDao().countAll())
            assertTrue(db.supplierDao().getAll().isEmpty())
            assertEquals(c.oldScope.ownerHash, db.businessDataScopeBindingDao().get()?.ownerHash)
            assertEquals(7L, db.syncEventWatermarkDao().get(c.OLD_OWNER, c.oldScope.storeId)?.lastSyncEventId)
            assertNull(db.syncEventWatermarkDao().get(c.OWNER, c.scope.storeId))
            assertNull(db.syncRecoveryBaselineDao().get())
        }
    }

    private suspend fun assertActive(db: AppDatabase, generation: String) {
        val c = RecoveryInterruptionContract
        assertNull(db.productDao().findByBarcode(c.OLD_BARCODE))
        assertEquals(0, db.productDao().count())
        assertEquals(0, db.productPriceDao().countAll())
        assertEquals(0, db.historyEntryDao().countUserVisible())
        assertEquals(0, db.syncEventOutboxDao().countAll())
        interruptionAssertSuppliers(db, listOf(c.FIRST, c.SECOND))
        assertEquals(c.scope.ownerHash, db.businessDataScopeBindingDao().get()?.ownerHash)
        assertEquals(c.scope.storeId, db.businessDataScopeBindingDao().get()?.storeId)
        assertEquals(c.FENCE, db.syncEventWatermarkDao().get(c.OWNER, c.scope.storeId)?.lastSyncEventId)
        val baseline = requireNotNull(db.syncRecoveryBaselineDao().get())
        assertEquals(generation, baseline.generationId)
        assertEquals(c.scope.ownerHash, baseline.ownerHash)
        assertEquals(c.SHOP, baseline.shopId)
        assertEquals(c.DEVICE, baseline.deviceId)
        db.withTransaction { validateShopSyncActiveReceipt(db, generation, decodeRecoveryCheckpointJson(baseline.checkpointJson)) }
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }
}
