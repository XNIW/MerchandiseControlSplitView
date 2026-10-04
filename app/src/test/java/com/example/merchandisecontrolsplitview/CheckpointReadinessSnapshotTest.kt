package com.example.merchandisecontrolsplitview

import com.example.merchandisecontrolsplitview.testutil.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CheckpointReadinessSnapshotTest {
    @get:Rule val dispatcherRule = MainDispatcherRule()

    @Test fun memorySnapshotsCannotInitializeEffectsOrConsumeAttempt() {
        val app = MerchandiseControlApplication() // No onCreate/storage/network fixture.
        val traps = installEffectSpies(app)
        val mutex = CountingMutex()
        setField(app, "businessRecoveryExecutionMutex", mutex)
        val scope = field<CoroutineScope>(app, "appScope")
        val childrenBefore = scope.coroutineContext[Job]!!.children.toList()
        try {
            repeat(3) {
                val snapshot = app.checkpointReadinessMemory(true, true, true)
                assertEquals(MerchandiseControlApplication.CheckpointReadinessOutcome.SNAPSHOT_INCOMPLETE, snapshot.outcome)
                assertEquals(true, snapshot.oneUseUnconsumed)
                assertNull(snapshot.sdkSession)
                assertNull(snapshot.refreshBudget)
                assertNull(snapshot.localScopeGuard)
                assertNull(snapshot.localSnapshotUnchanged)
                assertNull(snapshot.generationStable)
                assertNull(snapshot.businessGenerationQuiet)
                assertTrue(snapshot.safeLogLine().contains("business_READY=false"))
            }
            assertEquals(0, traps.sumOf { it.effectCalls })
            assertTrue(traps.all { !it.isInitialized() })
            assertEquals(0, mutex.tryLocks)
            assertEquals(0, mutex.unlocks)
            assertFalse(field<Boolean>(app, "checkpointTraceConsumed"))
            assertNull(field<Any?>(app, "checkpointTraceReservation"))
            assertNull(field<Job?>(app, "businessRecoveryJob"))
            assertEquals(childrenBefore, scope.coroutineContext[Job]!!.children.toList())
            assertFalse(mutex.isLocked)
        } finally { scope.cancel() } // Fixture teardown is outside the measured query.
    }

    @Test fun snapshotLeavesTheSubsequentRealOneShotConsumptionUnchanged() {
        val app = MerchandiseControlApplication()
        val traps = installEffectSpies(app)
        val mutex = CountingMutex()
        setField(app, "businessRecoveryExecutionMutex", mutex)
        val scope = field<CoroutineScope>(app, "appScope")
        try {
            ShadowLog.clear()
            app.checkpointReadinessMemory(true, true, true)
            assertFalse(field<Boolean>(app, "checkpointTraceConsumed"))
            assertEquals(0, mutex.tryLocks)
            // Same canonical synthetic unit profile as existing caller tests: target guard rejects.
            app.requestOneCheckpointTrace(true)
            app.requestOneCheckpointTrace(true)
            val traces = ShadowLog.getLogsForTag("Task143CheckpointTrace").map { it.msg }
            assertEquals(1, traces.count { it.contains("outcome=BLOCKED_TEST_TARGET") })
            assertEquals(1, traces.count { it.contains("outcome=BLOCKED_USED") })
            assertTrue(traces.all { it.contains("localRpcAttemptCount=0") })
            assertTrue(field<Boolean>(app, "checkpointTraceConsumed"))
            assertEquals(1, mutex.tryLocks)
            assertEquals(1, mutex.unlocks)
            assertEquals(0, traps.sumOf { it.effectCalls })
            assertNull(field<Any?>(app, "checkpointTraceReservation"))
        } finally { scope.cancel() }
    }

    @Test fun coldSnapshotDoesNotReadOrInitializeAnything() {
        val app = MerchandiseControlApplication()
        val traps = installEffectSpies(app)
        val mutex = CountingMutex()
        setField(app, "businessRecoveryExecutionMutex", mutex)
        val scope = field<CoroutineScope>(app, "appScope")
        try {
            val snapshot = app.checkpointReadinessMemory(false, false, true)
            assertEquals(MerchandiseControlApplication.CheckpointReadinessOutcome.BLOCKED_NOT_WARM, snapshot.outcome)
            assertNull(snapshot.oneUseUnconsumed)
            assertEquals(0, traps.sumOf { it.effectCalls })
            assertEquals(0, mutex.tryLocks)
            assertEquals(0, mutex.unlocks)
            assertFalse(field<Boolean>(app, "checkpointTraceConsumed"))
        } finally { scope.cancel() }
    }

    private fun installEffectSpies(app: MerchandiseControlApplication): List<EffectLazy> =
        listOf("supabaseClient", "authManager", "catalogSyncStateTracker", "shopContextRepository",
            "database", "shopSyncReadRemoteDataSource", "shopSyncRecoveryCoordinator", "deviceInstallIdProvider")
            .map { name -> EffectLazy().also { setField(app, name + "\$delegate", it) } }

    private class EffectLazy : Lazy<Any?> {
        var effectCalls = 0
        override val value: Any?
            get() { effectCalls++; error("Forbidden initializer/storage/SDK/RPC/recovery effect") }
        override fun isInitialized() = false
    }

    private class CountingMutex(private val delegate: Mutex = Mutex()) : Mutex by delegate {
        var tryLocks = 0
        var unlocks = 0
        override fun tryLock(owner: Any?): Boolean { tryLocks++; return delegate.tryLock(owner) }
        override fun unlock(owner: Any?) { unlocks++; delegate.unlock(owner) }
    }

    private fun setField(app: MerchandiseControlApplication, name: String, value: Any?) {
        app.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(app, value)
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(app: MerchandiseControlApplication, name: String): T =
        app.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(app) as T
}
