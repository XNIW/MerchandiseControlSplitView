package com.example.merchandisecontrolsplitview

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.work.Configuration
import androidx.work.WorkManager
import com.example.merchandisecontrolsplitview.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** Real Activity callbacks; synthetic app configuration deliberately fails the TEST target pin. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class CheckpointTraceActivityLifecycleTest {
    @get:Rule val dispatcherRule = MainDispatcherRule()

    @Before fun prepare() {
        val app = RuntimeEnvironment.getApplication() as MerchandiseControlApplication
        try { WorkManager.getInstance(app) }
        catch (_: IllegalStateException) {
            WorkManager.initialize(app, Configuration.Builder().build())
        }
        ShadowLog.clear()
    }

    @Test fun warmPausedNewIntentRunsOnceOnlyAfterResume() {
        val activity = Robolectric.buildActivity(MainActivity::class.java, ordinaryIntent())
            .create().start().resume()
        try {
            assertEquals(Lifecycle.State.RESUMED, activity.get().lifecycle.currentState)
            activity.pause()
            assertFalse(activity.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            val incoming = diagnosticIntent()
            activity.newIntent(incoming)
            assertFalse(incoming.hasExtra(EXTRA))
            assertTrue("Warm delivery must wait for the real resume callback", traces().isEmpty())
            activity.resume()
            assertEquals(1, traces().size)
            assertTrue("Warm request reaches the real target guard", traces().single().contains("outcome=BLOCKED_TEST_TARGET"))
            assertTrue(traces().single().contains("localRpcAttemptCount=0"))
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun coldIntentIsConsumedWithoutDiagnosticRpcAndNormalStartupContinues() {
        val incoming = diagnosticIntent()
        val activity = Robolectric.buildActivity(MainActivity::class.java, incoming).create().start().resume()
        try {
            assertFalse(incoming.hasExtra(EXTRA))
            assertEquals(Lifecycle.State.RESUMED, activity.get().lifecycle.currentState)
            assertEquals(1, traces().size)
            assertTrue(traces().single().contains("outcome=BLOCKED_COLD"))
            assertTrue(traces().single().contains("localRpcAttemptCount=0"))
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun stoppedBackgroundActivityDoesNotArmWarmTrace() {
        val activity = Robolectric.buildActivity(MainActivity::class.java, ordinaryIntent()).create().start().resume()
        activity.pause().stop()
        try {
            activity.newIntent(diagnosticIntent())
            activity.restart().start().resume()
            assertTrue(traces().isNotEmpty())
            assertTrue(traces().all { it.contains("localRpcAttemptCount=0") })
            assertFalse(traces().any { it.contains("outcome=BLOCKED_TEST_TARGET") })
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun destroyBeforeResumeCancelsPendingWarmDelivery() {
        val activity = Robolectric.buildActivity(MainActivity::class.java, ordinaryIntent()).create().start().resume()
        activity.pause().newIntent(diagnosticIntent())
        activity.stop().destroy()
        assertTrue("Destroyed Activity must never dispatch its pending trace", traces().isEmpty())
    }

    @Test fun repeatedWarmIntentCannotStartSecondAttempt() {
        val activity = Robolectric.buildActivity(MainActivity::class.java, ordinaryIntent()).create().start().resume()
        try {
            activity.pause().newIntent(diagnosticIntent()).resume()
            activity.pause().newIntent(diagnosticIntent()).resume()
            assertEquals(1, traces().count { it.contains("outcome=BLOCKED_TEST_TARGET") })
            assertTrue(traces().all { it.contains("localRpcAttemptCount=0") })
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun duplicatePendingWarmIntentsStillDispatchOnlyOnce() {
        val activity = Robolectric.buildActivity(MainActivity::class.java, ordinaryIntent()).create().start().resume()
        try {
            activity.pause()
            activity.newIntent(diagnosticIntent())
            activity.newIntent(diagnosticIntent())
            assertTrue("Both deliveries wait for resume", traces().isEmpty())
            activity.resume()
            assertEquals(1, traces().count { it.contains("outcome=BLOCKED_TEST_TARGET") })
            assertTrue(traces().all { it.contains("localRpcAttemptCount=0") })
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun ordinaryShareIntentStillReachesShareBusWithoutTrace() {
        val activity = Robolectric.buildActivity(MainActivity::class.java, ordinaryIntent()).create().start().resume()
        try {
            val uri = Uri.parse("content://synthetic-checkpoint-share/missing.xlsx")
            activity.pause().newIntent(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)).resume()
            assertEquals(listOf(uri), MainActivity.ShareBus.uris.replayCache.last())
            assertTrue(traces().isEmpty())
            assertFalse(activity.get().intent.hasExtra(Intent.EXTRA_STREAM))
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun readinessIntentNeverConsumesOrArmsTraceAndLaterTriggerStillRunsOnce() {
        val app = RuntimeEnvironment.getApplication() as MerchandiseControlApplication
        val activity = Robolectric.buildActivity(MainActivity::class.java, ordinaryIntent())
            .create().start().resume()
        try {
            val mixed = ordinaryIntent().putExtra("task143_checkpoint_readiness", true).putExtra(EXTRA, true)
            activity.pause().newIntent(mixed).resume()
            assertFalse(mixed.hasExtra("task143_checkpoint_readiness"))
            assertFalse(mixed.hasExtra(EXTRA))
            assertTrue(traces().isEmpty())
            val consumed = app.javaClass.getDeclaredField("checkpointTraceConsumed").apply { isAccessible = true }
            assertFalse(consumed.getBoolean(app))
            val observer = activity.get().javaClass.getDeclaredField("pendingCheckpointTraceObserver")
                .apply { isAccessible = true }
            assertNull(observer.get(activity.get()))
            assertTrue(ShadowLog.getLogsForTag("Task143CheckpointReadiness").single().msg
                .contains("outcome=BLOCKED_CONFLICT"))
            activity.pause().newIntent(ordinaryIntent().putExtra("task143_checkpoint_readiness", true)).resume()
            assertTrue(traces().isEmpty())
            assertFalse(consumed.getBoolean(app))
            assertNull(observer.get(activity.get()))
            activity.pause().newIntent(diagnosticIntent()).resume()
            activity.pause().newIntent(diagnosticIntent()).resume()
            assertEquals(1, traces().count { it.contains("outcome=BLOCKED_TEST_TARGET") })
            assertEquals(1, traces().count { it.contains("outcome=BLOCKED_USED") })
            assertTrue(traces().all { it.contains("localRpcAttemptCount=0") })
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun readinessSamplesActivityWhenQueryRunsAndDoesNotWaitForResume() {
        val app = RuntimeEnvironment.getApplication() as MerchandiseControlApplication
        val activity = Robolectric.buildActivity(MainActivity::class.java, ordinaryIntent()).create().start().resume()
        val queued = StandardTestDispatcher()
        Dispatchers.setMain(queued)
        try {
            activity.pause().newIntent(ordinaryIntent().putExtra("task143_checkpoint_readiness", true))
            assertTrue(ShadowLog.getLogsForTag("Task143CheckpointReadiness").isEmpty())
            activity.resume()
            queued.scheduler.runCurrent()
            assertTrue(ShadowLog.getLogsForTag("Task143CheckpointReadiness").single().msg.contains("activity_resumed=true"))
            ShadowLog.clear()
            activity.pause().newIntent(ordinaryIntent().putExtra("task143_checkpoint_readiness", true)).stop()
            queued.scheduler.runCurrent()
            assertTrue(ShadowLog.getLogsForTag("Task143CheckpointReadiness").single().msg.contains("activity_resumed=false"))
            assertFalse(app.javaClass.getDeclaredField("checkpointTraceConsumed").apply { isAccessible = true }.getBoolean(app))
            assertTrue(traces().isEmpty())
        } finally {
            Dispatchers.setMain(dispatcherRule.dispatcher)
            activity.destroy()
        }
    }

    @Test fun localThreadSnapshotColdDeliveryNeverArmsReadinessOrTrace() {
        val incoming = Intent(Intent.ACTION_MAIN).putExtra("task143_local_thread_snapshot", true)
        val activity = Robolectric.buildActivity(MainActivity::class.java, incoming).create()
        try {
            assertFalse(incoming.hasExtra("task143_local_thread_snapshot"))
            assertTrue(ShadowLog.getLogsForTag("Task143LocalThreads").single().msg.contains("outcome=BLOCKED_NOT_WARM"))
            assertTrue(traces().isEmpty())
            assertTrue(ShadowLog.getLogsForTag("Task143CheckpointReadiness").isEmpty())
            assertNull(activity.get().javaClass.getDeclaredField("pendingCheckpointTraceObserver")
                .apply { isAccessible = true }.get(activity.get()))
        } finally { activity.destroy() }
    }

    @Test fun localThreadSnapshotMixedDeliveryDisarmsAllDiagnosticAndSmokeExtras() {
        val activity = Robolectric.buildActivity(MainActivity::class.java, ordinaryIntent()).create().start().resume()
        val app = RuntimeEnvironment.getApplication() as MerchandiseControlApplication
        val beforeShare = MainActivity.ShareBus.uris.replayCache
        try {
            val mixed = Intent(Intent.ACTION_SEND)
                .putExtra("task143_local_thread_snapshot", true)
                .putExtra("task143_checkpoint_readiness", true).putExtra(EXTRA, true)
                .putExtra("task087_smoke", true).putExtra("task126_ui_smoke_kind", "checkpoint-lifecycle")
                .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://synthetic-thread-snapshot/missing.xlsx"))
            activity.pause().newIntent(mixed).resume()
            for (extra in listOf("task143_local_thread_snapshot", "task143_checkpoint_readiness", EXTRA,
                "task087_smoke", "task126_ui_smoke_kind")) assertFalse(mixed.hasExtra(extra))
            assertTrue(ShadowLog.getLogsForTag("Task143LocalThreads").single().msg.contains("outcome=BLOCKED_CONFLICT"))
            assertTrue(traces().isEmpty())
            assertTrue(ShadowLog.getLogsForTag("Task143CheckpointReadiness").isEmpty())
            assertTrue(ShadowLog.getLogsForTag("Task087Smoke").isEmpty())
            assertEquals(beforeShare, MainActivity.ShareBus.uris.replayCache)
            assertFalse(app.javaClass.getDeclaredField("checkpointTraceConsumed").apply { isAccessible = true }.getBoolean(app))
            assertNull(activity.get().javaClass.getDeclaredField("pendingCheckpointTraceObserver")
                .apply { isAccessible = true }.get(activity.get()))
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun localThreadSnapshotWarmNonTestAndStoppedDeliveriesNeverArmAResumeObserver() {
        val activity = Robolectric.buildActivity(MainActivity::class.java, ordinaryIntent()).create().start().resume()
        val queued = StandardTestDispatcher()
        Dispatchers.setMain(queued)
        try {
            activity.pause().newIntent(Intent(Intent.ACTION_MAIN).putExtra("task143_local_thread_snapshot", true))
            assertTrue(ShadowLog.getLogsForTag("Task143LocalThreads").isEmpty())
            activity.resume()
            queued.scheduler.runCurrent()
            assertTrue(ShadowLog.getLogsForTag("Task143LocalThreads").single().msg.contains("outcome=BLOCKED_TEST_TARGET"))
            ShadowLog.clear()
            activity.pause().stop().newIntent(Intent(Intent.ACTION_MAIN).putExtra("task143_local_thread_snapshot", true))
            queued.scheduler.runCurrent()
            assertTrue(ShadowLog.getLogsForTag("Task143LocalThreads").single().msg.contains("outcome=BLOCKED_NOT_WARM"))
            assertNull(activity.get().javaClass.getDeclaredField("pendingCheckpointTraceObserver")
                .apply { isAccessible = true }.get(activity.get()))
            assertTrue(traces().isEmpty())
        } finally {
            Dispatchers.setMain(dispatcherRule.dispatcher)
            activity.destroy()
        }
    }

    private fun ordinaryIntent() = Intent(Intent.ACTION_MAIN).putExtra("task126_ui_smoke_kind", "checkpoint-lifecycle")
    private fun diagnosticIntent() = ordinaryIntent().putExtra(EXTRA, true)
    private fun traces() = ShadowLog.getLogsForTag("Task143CheckpointTrace").map { it.msg }
    private companion object { const val EXTRA = "task143_checkpoint_trace" }
}
