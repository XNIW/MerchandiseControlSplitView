package com.example.merchandisecontrolsplitview.data

import android.content.Context
import androidx.room.Room
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HistoryMutationAtomicityTest {
    @Test
    fun historyPayloadAndRevisionRollBackTogetherWhenSecondWriteFailsAndDatabaseReopens() = runTest {
        val context: Context = RuntimeEnvironment.getApplication()
        val databaseName = "task144-history-atomicity-${UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .allowMainThreadQueries().build()
        try {
            val owner = "00000000-0000-4000-8000-000000000911"
            val shop = SelectedShop(shopId = "00000000-0000-4000-8000-000000000912",
                code = "HISTORY_ATOMIC", name = "History atomic fixture", role = "shop_owner",
                status = "active", canWrite = true)
            activateEmptyV6EventRecoveryFixture(context, database, owner, shop)
            val qualified = DefaultInventoryRepository(database)
                .resolveBusinessDataScope(task126ActiveOwnerStoreScope(owner, shop))
            assertEquals(Task126BusinessDataScopeStatus.READY, qualified.status)
            val tracker = CatalogSyncStateTracker(qualified)
            val repository = DefaultInventoryRepository(database, businessDataScopeRuntimeGuard = tracker)
            val payload = SessionRemotePayload(
                remoteId = "00000000-0000-4000-8000-000000000913",
                payloadVersion = SESSION_PAYLOAD_VERSION,
                displayName = "History before atomic edit",
                timestamp = "2026-10-16 12:00:00", supplier = "", category = "",
                isManualEntry = true,
                data = listOf(listOf("barcode", "qty"), listOf("HISTORY-ATOMIC-1", "2")),
                sessionOverlay = SessionOverlay(
                    editable = listOf(listOf("", ""), listOf("", "")),
                    complete = listOf(false, false)
                )
            )
            val applied = tracker.withBusinessDataScopeFlight(owner, shop) {
                repository.applyRemoteSessionPayload(payload)
            }
            assertEquals(RemoteSessionApplyOutcome.Inserted, applied)
            val beforeRef = requireNotNull(database.historyEntryRemoteRefDao().getByRemoteId(payload.remoteId))
            val before = requireNotNull(repository.getHistoryEntryByUid(beforeRef.historyEntryUid))
            val baselineBefore = requireNotNull(database.syncRecoveryBaselineDao().get())
            val bindingBefore = requireNotNull(database.businessDataScopeBindingDao().get())
            assertEquals(payload.displayName, before.displayName)
            assertEquals(beforeRef.localChangeRevision, beforeRef.lastSyncedLocalRevision)
            assertNotNull(beforeRef.lastRemoteAppliedAt)
            assertEquals(payload.payloadFingerprint(), beforeRef.lastRemotePayloadFingerprint)
            assertTrue(repository.getPendingHistorySessionPushUids().isEmpty())
            assertEquals(1, database.historyEntryDao().countUserVisible())
            assertEquals(0, database.syncEventOutboxDao().countAll())
            var notifications = 0
            repository.onHistorySessionPayloadChanged = { notifications++ }
            val edited = before.copy(displayName = "History after atomic edit")
            val fault = "task144_history_second_revision_write"
            // The trigger aborts ONLY the revision increment, and ONLY after the first
            // payload write is visible. It cannot turn a failed first write into this witness.
            database.openHelper.writableDatabase.execSQL("""
                CREATE TRIGGER task144_fail_history_second_write
                BEFORE UPDATE OF localChangeRevision ON history_entry_remote_refs
                WHEN OLD.historyEntryUid = ${before.uid}
                  AND NEW.localChangeRevision = OLD.localChangeRevision + 1
                  AND EXISTS (SELECT 1 FROM history_entries
                      WHERE uid = ${before.uid} AND displayName = 'History after atomic edit')
                BEGIN
                    SELECT RAISE(ABORT, 'task144_history_second_revision_write');
                END
            """.trimIndent())
            val failure = runCatching { repository.updateHistoryEntry(edited) }.exceptionOrNull()
            assertNotNull("The second-write fault must be reached", failure)
            assertTrue("The failure must come from the conditional revision trigger",
                generateSequence(failure) { it.cause }.any { it.message?.contains(fault) == true })
            assertEquals(0, notifications)

            // A real file reopen, not a coroutine cancellation or an in-memory snapshot.
            database.close()
            database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
                .allowMainThreadQueries().build()
            val after = requireNotNull(database.historyEntryDao().getByUid(before.uid))
            val afterRef = requireNotNull(database.historyEntryRemoteRefDao().getByHistoryEntryUid(before.uid))
            println("history_atomicity secondWriteFault=true reopened=true " +
                "payloadChanged=${after != before} revision=${afterRef.localChangeRevision} " +
                "synced=${afterRef.lastSyncedLocalRevision} notifications=$notifications")
            assertEquals(beforeRef, afterRef)
            assertEquals(baselineBefore, database.syncRecoveryBaselineDao().get())
            assertEquals(bindingBefore, database.businessDataScopeBindingDao().get())
            assertNull(database.syncRecoveryJournalDao().get())
            assertEquals(1, database.historyEntryDao().countUserVisible())
            assertEquals(0, database.syncEventOutboxDao().countAll())
            assertEquals("History payload and revision must roll back together after second write failure",
                before, after)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
