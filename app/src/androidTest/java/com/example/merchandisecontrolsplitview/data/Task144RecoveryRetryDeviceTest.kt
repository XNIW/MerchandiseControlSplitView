package com.example.merchandisecontrolsplitview.data

import android.os.Process
import android.os.SystemClock
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native scheduler/service proof only. No onCreate/UI, manual Retry, fake clock or backend. */
@RunWith(AndroidJUnit4::class)
class Task144RecoveryRetryDeviceTest {
    @Test
    fun prepareMixedFaultsAtPersistedCooldown(): Unit = runBlocking {
        withTimeout(120_000) {
            val context = t05OwnedContext("mixed", true)
            val f = T05Fixture(context)
            try {
                f.openTransport(); f.seed()
                val body = f.snapshot("products")
                val ref = f.snapshot("product_remote_refs")
                val baseline = f.snapshot("sync_recovery_baseline")
                val initial = f.journal()
                assertEquals(0, initial.attemptCount)
                f.attachApplication()
                f.application.requestPendingBusinessRecovery("task144_native_initial")
                val job = requireNotNull(f.recoveryJob())
                f.application.requestPendingBusinessRecovery("task144_native_duplicate_initial")
                assertSame(job, f.recoveryJob())
                awaitCondition {
                    val j = f.db.syncRecoveryJournalDao().get()
                    val m = j?.let { AutomaticRecoveryRetryMetadata.decode(it.reason) }
                    j?.attemptCount == 5 && m?.state == "cooldown" && !f.mutex().isLocked
                }
                val exhausted = f.journal()
                val metadata = requireNotNull(AutomaticRecoveryRetryMetadata.decode(exhausted.reason))
                assertEquals(5, metadata.windowAttempts)
                assertEquals(SyncErrorCategory.RateLimited, metadata.category)
                assertEquals(initial.createdAtMs, exhausted.createdAtMs)
                assertEquals(initial.ownerHash, exhausted.ownerHash); assertEquals(initial.storeScope, exhausted.storeScope)
                assertEquals(initial.shopId, exhausted.shopId); assertEquals(initial.deviceId, exhausted.deviceId)
                assertEquals(initial.authorizationMode, exhausted.authorizationMode)
                val failures = logical(f).filter { it.getValue("attempt").jsonPrimitive.int in 1..5 }
                assertEquals(5, failures.size)
                assertEquals(listOf("RemoteTransient", "NetworkOfflineOrTimeout", "RemoteTransient", "NetworkOfflineOrTimeout", "RateLimited"),
                    failures.map { it.getValue("category").jsonPrimitive.content })
                assertEquals(listOf(1, 2, 3, 4, 5), failures.map { it.getValue("attempt").jsonPrimitive.int })
                val http = physical(f).filter { it.getValue("attempt").jsonPrimitive.int in 1..5 }
                assertEquals(5, http.size)
                assertEquals(listOf(1, 2, 3, 4, 5), http.map { it.getValue("window").jsonPrimitive.int })
                assertEquals(5, http.map { it.getValue("claim").jsonPrimitive.content }.distinct().size)
                assertTrue(failures.all { !it.getValue("invalidRetryAfter").jsonPrimitive.boolean })
                val third = failures[2]
                assertEquals(503, third.getValue("status").jsonPrimitive.int)
                assertEquals(third.getValue("completedMs").jsonPrimitive.long + 13_000, third.getValue("floor").jsonPrimitive.long)
                assertTrue(failures[3].getValue("startMs").jsonPrimitive.long >= third.getValue("floor").jsonPrimitive.long)
                val fifth = failures[4]
                assertEquals(429, fifth.getValue("status").jsonPrimitive.int)
                val serverFloor = fifth.getValue("floor").jsonPrimitive.long
                assertEquals(fifth.getValue("completedMs").jsonPrimitive.long + 310_000, serverFloor)
                assertTrue(metadata.nextEligibleAtMs >= serverFloor)
                assertTrue(metadata.nextEligibleAtMs >= requireNotNull(metadata.lastCompletedAtMs) + 300_000)
                assertEquals(metadata.nextEligibleAtMs, exhausted.nextRetryAtMs)
                assertEquals(body, f.snapshot("products")); assertEquals(ref, f.snapshot("product_remote_refs"))
                assertEquals(baseline, f.snapshot("sync_recovery_baseline"))
                assertTrue(f.ref().localChangeRevision > f.ref().lastSyncedLocalRevision)
                assertFalse(f.mutex().isLocked)
                f.application.requestPendingBusinessRecovery("task144_native_duplicate_cooldown")
                assertSame(job, f.recoveryJob())
                assertEquals(exhausted, f.journal())
                val marker = buildJsonObject {
                    put("protocol", T05Contract.PROTOCOL); put("runId", context.runId); put("scenario", "mixed")
                    put("pid", Process.myPid()); put("wallMs", System.currentTimeMillis()); put("elapsedNs", SystemClock.elapsedRealtimeNanos())
                    put("journal", f.snapshot("sync_recovery_journal")); put("products", body); put("refs", ref); put("baseline", baseline)
                    put("nextEligibleAtMs", metadata.nextEligibleAtMs); put("serverFloorMs", serverFloor)
                    put("fifthCompletionMs", requireNotNull(metadata.lastCompletedAtMs)); put("intentCreatedAtMs", initial.createdAtMs)
                    put("ledgerSha256", t05Sha(File(context.root, "transport.jsonl").readBytes()))
                }
                t05DurableWrite(File(context.root, "ready-for-force-stop.json"), marker.toString().encodeToByteArray())
                awaitCancellation() // Host must force-stop the owned process; timeout is FAIL.
            } finally { f.close() }
        }
    }

    @Test
    fun verifyReopenWaitsRealDeadlineThenRecoversWithoutRetry(): Unit = runBlocking {
        withTimeout(420_000) {
            val context = t05OwnedContext("mixed", false)
            val marker = T05Contract.json.parseToJsonElement(File(context.root, "ready-for-force-stop.json").readText()).jsonObject
            assertEquals(T05Contract.PROTOCOL, marker.getValue("protocol").jsonPrimitive.content)
            assertEquals(context.runId, marker.getValue("runId").jsonPrimitive.content)
            assertNotEquals(marker.getValue("pid").jsonPrimitive.int, Process.myPid())
            val f = T05Fixture(context)
            try {
                assertEquals(marker.getValue("journal").jsonPrimitive.content, f.snapshot("sync_recovery_journal"))
                assertEquals(marker.getValue("products").jsonPrimitive.content, f.snapshot("products"))
                assertEquals(marker.getValue("refs").jsonPrimitive.content, f.snapshot("product_remote_refs"))
                assertEquals(marker.getValue("baseline").jsonPrimitive.content, f.snapshot("sync_recovery_baseline"))
                assertEquals(marker.getValue("ledgerSha256").jsonPrimitive.content, t05Sha(File(context.root, "transport.jsonl").readBytes()))
                val before = f.journal()
                val deadline = marker.getValue("nextEligibleAtMs").jsonPrimitive.long
                assertTrue("Reopen must actually observe an unexpired cooldown", System.currentTimeMillis() < deadline - 1_000)
                checkClock(marker)
                f.openTransport(); f.attachApplication()
                f.application.requestPendingBusinessRecovery("task144_native_reopen")
                val job = requireNotNull(f.recoveryJob())
                f.application.requestPendingBusinessRecovery("task144_native_reopen_duplicate")
                assertSame(job, f.recoveryJob())
                while (System.currentTimeMillis() < deadline - 250) {
                    checkClock(marker)
                    assertEquals(before, f.journal())
                    assertEquals(5, physical(f).count { it.getValue("attempt").jsonPrimitive.int > 0 })
                    assertFalse(f.mutex().isLocked)
                    delay(100)
                }
                awaitCondition {
                    f.db.syncRecoveryJournalDao().get() == null &&
                        f.tracker.businessDataScopeState.value.status == Task126BusinessDataScopeStatus.READY && f.recoveryJob() == null
                }
                checkClock(marker)
                val observed = logical(f).filter { it.getValue("attempt").jsonPrimitive.int > 0 }
                assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 7), observed.map { it.getValue("attempt").jsonPrimitive.int })
                assertEquals("NetworkOfflineOrTimeout", observed[5].getValue("category").jsonPrimitive.content)
                assertEquals(listOf("success", "success"), observed.takeLast(2).map { it.getValue("category").jsonPrimitive.content })
                val sixth = observed[5]
                assertTrue(sixth.getValue("startMs").jsonPrimitive.long >= deadline)
                assertTrue(sixth.getValue("startMs").jsonPrimitive.long >= marker.getValue("serverFloorMs").jsonPrimitive.long)
                assertTrue(sixth.getValue("startMs").jsonPrimitive.long >= marker.getValue("fifthCompletionMs").jsonPrimitive.long + 300_000)
                val fifth = observed[4]
                assertTrue(sixth.getValue("startElapsedNs").jsonPrimitive.long - fifth.getValue("completedElapsedNs").jsonPrimitive.long >= 300_000_000_000L)
                val http = physical(f).filter { it.getValue("attempt").jsonPrimitive.int > 0 }
                val checkpoints = http.filter { it.getValue("endpoint").jsonPrimitive.content == T05Contract.CHECKPOINT_RPC }
                assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 7), checkpoints.map { it.getValue("attempt").jsonPrimitive.int })
                assertEquals(listOf(1, 2, 3, 4, 5, 1, 2, 2), checkpoints.map { it.getValue("window").jsonPrimitive.int })
                assertEquals(7, checkpoints.map { it.getValue("claim").jsonPrimitive.content }.distinct().size)
                assertEquals(6, http.count { it.getValue("endpoint").jsonPrimitive.content == T05Contract.PAGE_RPC })
                val markers = http.filter { it.getValue("endpoint").jsonPrimitive.content == T05Contract.MARKER_RPC }
                assertEquals(2, markers.size)
                assertEquals(listOf(SyncRecoveryJournalPhases.STAGING, SyncRecoveryJournalPhases.ACTIVATED_CLEANUP_PENDING),
                    markers.map { it.getValue("journalPhase").jsonPrimitive.content })
                assertTrue(markers.all { it.getValue("attempt").jsonPrimitive.int == 7 &&
                    it.getValue("claim") == checkpoints.last().getValue("claim") &&
                    it.getValue("fault").jsonPrimitive.content == "healthy" })
                assertEquals(1, markers.map { it.getValue("generationId").jsonPrimitive.content }.distinct().size)
                assertEquals(requireNotNull(f.db.syncRecoveryBaselineDao().get()).generationId,
                    markers.last().getValue("generationId").jsonPrimitive.content)
                assertTrue(http.all { it.getValue("intentCreatedAtMs").jsonPrimitive.long == marker.getValue("intentCreatedAtMs").jsonPrimitive.long })
                assertTrue(http.all { it.getValue("ownerHash").jsonPrimitive.content == T05Contract.scope.ownerHash &&
                    it.getValue("storeScope").jsonPrimitive.content == T05Contract.scope.storeId &&
                    it.getValue("deviceId").jsonPrimitive.content == T05Contract.DEVICE })
                assertTrue(http.all { it.getValue("wallMs").jsonPrimitive.long >= it.getValue("nextEligibleAtMs").jsonPrimitive.long })
                assertEquals(marker.getValue("products").jsonPrimitive.content, f.snapshot("products"))
                assertEquals(marker.getValue("refs").jsonPrimitive.content, f.snapshot("product_remote_refs"))
                assertTrue(f.ref().localChangeRevision > f.ref().lastSyncedLocalRevision)
                val retained = readLocalBusinessEntries(f.db, T05Contract.OWNER, T05Contract.scope.storeId)
                assertEquals(retained.size, f.db.syncEventOutboxDao().countAll())
                assertTrue(retained.isNotEmpty())
                assertTrue(retained.all { it.eventType == BUSINESS_RESOLUTION_EVENT })
                val effective = requireNotNull(loadBusinessResolutionSnapshot(f.db,
                    T05Contract.OWNER, T05Contract.scope.storeId, retained)).requireValid()
                assertTrue(effective.records.any { it.kind == "generation_transfer" })
                val pendingRef = f.ref()
                assertEquals(BusinessResolutionLedger.EffectiveRevision.Active,
                    effective.effectiveRevision(pendingRef.remoteId, pendingRef.localChangeRevision))
                val intent = requireNotNull(effective.intents.single { it.remoteId == pendingRef.remoteId &&
                    it.intent?.revision == pendingRef.localChangeRevision }.intent)
                assertEquals("Pending survives recovery", intent.desired.productName)
                assertTrue(intent.dispatchOperationKeys.isEmpty())
                assertEquals(T05Contract.scope.ownerHash, f.db.businessDataScopeBindingDao().get()?.ownerHash)
                assertEquals(T05Contract.scope.storeId, f.db.businessDataScopeBindingDao().get()?.storeId)
                val baseline = requireNotNull(f.db.syncRecoveryBaselineDao().get())
                f.db.withTransaction { validateShopSyncActiveReceipt(f.db, baseline.generationId, decodeRecoveryCheckpointJson(baseline.checkpointJson)) }
                assertFalse(f.mutex().isLocked)
                val count = physical(f).size
                delay(1_000)
                assertEquals(count, physical(f).size)
                t05DurableWrite(File(context.root, "verified.json"), buildJsonObject {
                    put("status", "PASS"); put("protocol", T05Contract.PROTOCOL); put("runId", context.runId)
                    put("scenario", "mixed"); put("pid", Process.myPid()); put("preparePid", marker.getValue("pid"))
                    put("logicalClaims", 7); put("checkpointCalls", 8); put("pageCalls", 6)
                    put("markerCalls", 2)
                    put("sixthStartedMs", sixth.getValue("startMs")); put("persistedDeadlineMs", deadline)
                    put("ledgerSha256", t05Sha(File(context.root, "transport.jsonl").readBytes()))
                }.toString().encodeToByteArray())
            } finally { f.close() }
        }
    }

    @Test
    fun stable403RemainsBlockedAfterItsPersistedDeadline(): Unit = runBlocking {
        withTimeout(60_000) {
            val context = t05OwnedContext("stable", true)
            val f = T05Fixture(context)
            try {
                f.openTransport(); f.seed()
                val body = f.snapshot("products"); val refs = f.snapshot("product_remote_refs")
                f.attachApplication()
                f.application.requestPendingBusinessRecovery("task144_native_stable_initial")
                awaitCondition { f.recoveryJob() == null && AutomaticRecoveryRetryMetadata.decode(f.journal().reason)?.state == "blocked" }
                val blocked = f.journal()
                val metadata = requireNotNull(AutomaticRecoveryRetryMetadata.decode(blocked.reason))
                assertEquals(1, blocked.attemptCount); assertEquals(1, metadata.windowAttempts)
                assertEquals(SyncErrorCategory.RemoteForbiddenRls, metadata.category)
                val result = logical(f).single { it.getValue("attempt").jsonPrimitive.int > 0 }
                assertEquals(403, result.getValue("status").jsonPrimitive.int)
                assertEquals("RemoteForbiddenRls", result.getValue("category").jsonPrimitive.content)
                val deadline = requireNotNull(blocked.nextRetryAtMs)
                assertEquals(metadata.nextEligibleAtMs, deadline)
                delay((deadline - System.currentTimeMillis()).coerceAtLeast(0) + 1_000)
                assertTrue(System.currentTimeMillis() > deadline)
                f.application.requestPendingBusinessRecovery("task144_native_stable_reconnect")
                f.application.requestPendingBusinessRecovery("task144_native_stable_foreground")
                delay(1_000)
                assertNull(f.recoveryJob()); assertFalse(f.mutex().isLocked)
                assertEquals(blocked, f.journal())
                assertEquals(1, physical(f).count { it.getValue("attempt").jsonPrimitive.int > 0 })
                assertEquals(body, f.snapshot("products")); assertEquals(refs, f.snapshot("product_remote_refs"))
                assertTrue(f.ref().localChangeRevision > f.ref().lastSyncedLocalRevision)
                t05DurableWrite(File(context.root, "verified.json"), buildJsonObject {
                    put("status", "PASS"); put("protocol", T05Contract.PROTOCOL); put("runId", context.runId)
                    put("scenario", "stable"); put("pid", Process.myPid()); put("persistedDeadlineMs", deadline)
                    put("observedAfterMs", System.currentTimeMillis()); put("logicalClaims", 1)
                    put("ledgerSha256", t05Sha(File(context.root, "transport.jsonl").readBytes()))
                }.toString().encodeToByteArray())
            } finally { f.close() }
        }
    }

    private fun logical(f: T05Fixture) = f.records().filter { it.getValue("kind").jsonPrimitive.content == "checkpoint_result" }
    private fun physical(f: T05Fixture) = f.records().filter { it.getValue("kind").jsonPrimitive.content == "http" }
    private suspend fun awaitCondition(predicate: suspend () -> Boolean) {
        while (!predicate()) delay(50) // Only observes production timers; never advances or changes them.
    }
    private fun checkClock(marker: JsonObject) {
        val wallDelta = System.currentTimeMillis() - marker.getValue("wallMs").jsonPrimitive.long
        val monoDelta = (SystemClock.elapsedRealtimeNanos() - marker.getValue("elapsedNs").jsonPrimitive.long) / 1_000_000
        assertTrue("Clock discontinuity makes real-time witness unqualified", abs(wallDelta - monoDelta) <= 2_500)
        assertTrue("Elapsed clock must survive process reopen on the same boot", monoDelta >= 0)
    }
}
