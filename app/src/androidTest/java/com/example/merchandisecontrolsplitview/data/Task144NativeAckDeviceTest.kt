package com.example.merchandisecontrolsplitview.data

import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native repository/Room witnesses only: not UI Save, scheduler liveness or authenticated E2E.
 * Single-method selection and the neutral Application runner are mandatory, never assumed/skipped.
 * Each run uses a fresh owned directory. No cleanup removes DBs or failed-run evidence.
 */
@RunWith(AndroidJUnit4::class)
class Task144NativeAckDeviceTest {
    @Test
    fun saveBDuringHeldAckAPreservesRevisionThenDrainsOnce(): Unit = runBlocking {
        withTimeout(90_000) {
            val context = nativeOwnedContext("save-b", fresh = true)
            var revisionB = -1
            var generation = ""
            NativeAckFixture(context).use { fixture ->
                fixture.seed()
                generation = requireNotNull(fixture.db.syncRecoveryBaselineDao().get()).generationId
                val beforeA = fixture.ref()
                fixture.save("Save A")
                val revisionA = fixture.ref().localChangeRevision
                assertTrue(revisionA > beforeA.localChangeRevision)
                coroutineScope {
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    fixture.backend.afterPatchCommit = { entered.complete(Unit); release.await() }
                    val flight = async(Dispatchers.IO) { fixture.push() }
                    try {
                        entered.await() // Ledger A has been fsynced; transport has NOT returned ACK.
                        assertEquals("Save A", fixture.backend.state().product?.productName)
                        val receiptA = fixture.patchReceipt()
                        assertEquals(1, receiptA.attemptCount)
                        fixture.save("Save B")
                        revisionB = fixture.ref().localChangeRevision
                        assertTrue(revisionB > revisionA)
                        assertEquals("Save B", fixture.product().productName)
                        assertEquals(beforeA.lastSyncedLocalRevision, fixture.ref().lastSyncedLocalRevision)
                        assertEquals(receiptA, fixture.patchReceipt())
                        release.complete(Unit)
                        flight.await()
                    } finally {
                        withContext(NonCancellable) { flight.cancelAndJoin() }
                        fixture.backend.afterPatchCommit = null
                    }
                }
                assertEquals("Save B", fixture.product().productName)
                assertEquals(revisionB, fixture.ref().localChangeRevision)
                assertEquals(revisionA, fixture.ref().lastSyncedLocalRevision)
                assertEquals("productname", fixture.ref().localChangedFields)
                assertTrue(fixture.db.productRemoteRefDao().hasPendingWork())
                fixture.assertScopedOutbox(expectedWrites = 0)
                assertTrue(fixture.repository.hasCatalogCloudPendingWorkInclusive())
                fixture.push()
                fixture.assertClean(revisionB, generation)
                val calls = fixture.backend.state().calls
                assertEquals(listOf("PRODUCTS", "PATCH", "PATCH"), calls.map { it.kind })
                assertEquals(listOf("Save A", "Save B"), calls.drop(1).map { it.patch?.productName })
                assertNotEquals(calls[1].operationKey, calls[2].operationKey)
                assertEquals(3, fixture.backend.state().logicalMutations) // Seed + A + B.
                assertEquals("Save B", fixture.backend.state().product?.productName)
                fixture.push()
                assertEquals(calls, fixture.backend.state().calls)
            }
            // Actual native Room close/reopen after both ACKs, with the same owned path.
            NativeAckFixture(NativeAckOwnedContext(context.baseContext, context.runId, "save-b", fresh = false)).use { reopened ->
                reopened.qualify()
                reopened.assertClean(revisionB, generation)
                val calls = reopened.backend.state().calls
                reopened.push()
                assertEquals(calls, reopened.backend.state().calls)
            }
            nativeDurableWrite(File(context.root, "verified.json"), NativeAckContract.json.encodeToString(
                NativeAckVerification(context.runId, "save-b", Process.myPid(), null, revisionB, generation, 3, 3)
            ).encodeToByteArray())
        }
    }

    @Test
    fun prepareLostAckAfterRemoteCommitAndSaveB(): Unit = runBlocking {
        // Intentional hold: only the external owned marker/force-stop harness completes this phase.
        // A timeout is a failure, never a successful process-death witness.
        withTimeout(90_000) {
            val context = nativeOwnedContext("lost-ack", fresh = true)
            NativeAckFixture(context).use { fixture ->
                fixture.seed()
                val generation = requireNotNull(fixture.db.syncRecoveryBaselineDao().get()).generationId
                val beforeA = fixture.ref()
                fixture.save("Save A")
                val revisionA = fixture.ref().localChangeRevision
                coroutineScope {
                    val entered = CompletableDeferred<Unit>()
                    fixture.backend.afterPatchCommit = { entered.complete(Unit); awaitCancellation() }
                    val flight = async(Dispatchers.IO) { fixture.push() }
                    try {
                        entered.await()
                        assertTrue(flight.isActive)
                        val receiptA = fixture.patchReceipt()
                        assertEquals(1, receiptA.attemptCount)
                        fixture.save("Save B")
                        val revisionB = fixture.ref().localChangeRevision
                        assertTrue(revisionB > revisionA && revisionA > beforeA.localChangeRevision)
                        assertEquals(beforeA.lastSyncedLocalRevision, fixture.ref().lastSyncedLocalRevision)
                        assertEquals("Save B", fixture.product().productName)
                        assertEquals(receiptA, fixture.patchReceipt())
                        assertEquals("Save A", fixture.backend.state().product?.productName)
                        assertEquals(2, fixture.backend.state().calls.size)
                        assertEquals(2, fixture.backend.state().logicalMutations)
                        // Separate connection proves B is visible outside the repository's writer connection.
                        val readback = android.database.sqlite.SQLiteDatabase.openDatabase(
                            context.getDatabasePath(NativeAckContract.DB).path, null,
                            android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
                        try {
                            readback.rawQuery("SELECT productName FROM products WHERE id = ?", arrayOf(fixture.product().id.toString())).use {
                                assertTrue(it.moveToFirst()); assertEquals("Save B", it.getString(0)); assertFalse(it.moveToNext())
                            }
                        } finally { readback.close() }
                        val marker = NativeAckProcessMarker(context.runId, Process.myPid(), SystemClock.elapsedRealtime(),
                            fixture.product().id, beforeA.lastSyncedLocalRevision, revisionA, revisionB,
                            generation, receiptA.clientEventId, receiptA.metadataJson,
                            nativeSha(fixture.backend.file.readBytes()))
                        nativeDurableWrite(File(context.root, "ready-for-force-stop.json"), NativeAckContract.json.encodeToString(marker).encodeToByteArray())
                        awaitCancellation() // No synthetic exception/close is substituted for Android process death.
                    } finally { withContext(NonCancellable) { flight.cancelAndJoin() } }
                }
            }
        }
    }

    @Test
    fun verifyLostAckInNewProcessReplaysABeforeB(): Unit = runBlocking {
        withTimeout(90_000) {
            val context = nativeOwnedContext("lost-ack", fresh = false)
            val marker = NativeAckContract.json.decodeFromString<NativeAckProcessMarker>(
                File(context.root, "ready-for-force-stop.json").readText())
            assertEquals(context.runId, marker.runId)
            assertEquals(NativeAckContract.PROTOCOL, marker.protocol)
            assertNotEquals("A distinct native process is required", marker.preparePid, Process.myPid())
            NativeAckFixture(context).use { fixture ->
                assertEquals(marker.serverSha256, nativeSha(fixture.backend.file.readBytes()))
                assertEquals(2, fixture.backend.state().calls.size)
                assertEquals("Save A", fixture.backend.state().product?.productName)
                assertEquals(marker.localProductId, fixture.product().id)
                assertEquals("Save B", fixture.product().productName)
                assertEquals(marker.revisionB, fixture.ref().localChangeRevision)
                assertEquals(marker.lastSyncedBeforeA, fixture.ref().lastSyncedLocalRevision)
                assertEquals("productname", fixture.ref().localChangedFields)
                val before = fixture.patchReceipt()
                assertEquals(marker.operationKeyA, before.clientEventId)
                assertEquals(marker.rawAttemptA, before.metadataJson)
                assertEquals(1, before.attemptCount)
                fixture.assertScopedOutbox(expectedWrites = 1)
                assertEquals(marker.generation, fixture.db.syncRecoveryBaselineDao().get()?.generationId)
                fixture.qualify() // Resolve the persisted scope; no replacement recovery or seed.
                assertTrue(fixture.repository.hasCatalogCloudPendingWorkInclusive())
                fixture.push()
                val state = fixture.backend.state()
                assertEquals(listOf("PRODUCTS", "PATCH", "PATCH", "PATCH"), state.calls.map { it.kind })
                assertEquals(listOf("Save A", "Save A", "Save B"), state.calls.drop(1).map { it.patch?.productName })
                val originalA = state.calls[1]
                val replayA = state.calls[2]
                val successorB = state.calls[3]
                assertEquals(originalA.copy(stampedAttemptCount = 2), replayA)
                assertEquals(marker.rawAttemptA, replayA.rawAttempt)
                assertEquals(marker.operationKeyA, replayA.operationKey)
                assertEquals(marker.revisionA.toLong(), NativeAckContract.json.decodeFromString<BusinessWriteAttempt>(replayA.rawAttempt).payload.revisions.single().revision)
                assertEquals(marker.revisionB.toLong(), NativeAckContract.json.decodeFromString<BusinessWriteAttempt>(successorB.rawAttempt).payload.revisions.single().revision)
                assertNotEquals(originalA.operationKey, successorB.operationKey)
                assertEquals(1, successorB.stampedAttemptCount)
                assertEquals(3, state.logicalMutations) // Replayed A is observed but does not change the synthetic row.
                assertEquals("Save B", state.product?.productName)
                fixture.assertClean(marker.revisionB, marker.generation)
                fixture.push()
                assertEquals(state, fixture.backend.state())
            }
            NativeAckFixture(NativeAckOwnedContext(context.baseContext, context.runId, "lost-ack", fresh = false)).use { reopened ->
                reopened.qualify()
                reopened.assertClean(marker.revisionB, marker.generation)
                val state = reopened.backend.state()
                reopened.push()
                assertEquals(state, reopened.backend.state())
            }
            nativeDurableWrite(File(context.root, "verified.json"), NativeAckContract.json.encodeToString(
                NativeAckVerification(context.runId, "lost-ack", Process.myPid(), marker.preparePid,
                    marker.revisionB, marker.generation, 4, 3)
            ).encodeToByteArray())
        }
    }
}

@Serializable
internal data class NativeAckProcessMarker(
    val runId: String, val preparePid: Int, val elapsedRealtimeMs: Long, val localProductId: Long,
    val lastSyncedBeforeA: Int, val revisionA: Int, val revisionB: Int, val generation: String,
    val operationKeyA: String, val rawAttemptA: String, val serverSha256: String,
    val protocol: String = NativeAckContract.PROTOCOL
)
@Serializable
internal data class NativeAckVerification(
    val runId: String, val scenario: String, val verifyPid: Int, val preparePid: Int?,
    val revisionB: Int, val generation: String, val observedCalls: Int, val logicalMutations: Int,
    val protocol: String = NativeAckContract.PROTOCOL
)
