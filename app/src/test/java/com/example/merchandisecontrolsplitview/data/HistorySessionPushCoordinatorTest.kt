package com.example.merchandisecontrolsplitview.data

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistorySessionPushCoordinatorTest {

    @Test
    fun `114 default history auto push debounce stays within near realtime budget`() {
        assertEquals(500L, HistorySessionPushCoordinator.DEBOUNCE_MS)
    }

    @Test
    fun `040 runPushCycle uses precise pending uid set`() = runTest {
        val repository = mockk<InventoryRepository>()
        val logs = mutableListOf<String>()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(
                userId = "00000000-0000-4000-8000-000000000040",
                email = "user@example.test"
            )
        )
        coEvery { repository.getPendingHistorySessionPushUids() } returns listOf(10L, 11L)
        coEvery {
            repository.pushHistorySessionsToRemote(any(), "00000000-0000-4000-8000-000000000040", setOf(10L, 11L))
        } returns Result.success(
            HistorySessionBackupPushSummary(
                uploaded = 2,
                skippedAlreadySynced = 0,
                attempted = 2
            )
        )
        val coordinator = HistorySessionPushCoordinator(
            repository = repository,
            remote = FakeConfiguredSessionRemote040(),
            authFlow = auth,
            flightOwner = SessionCloudSessionFlightOwner(logger = logs::add),
            scope = backgroundScope,
            debounceMs = 1L,
            logger = logs::add
        )

        coordinator.runPushCycle("debounce_fired")

        coVerify(exactly = 1) { repository.getPendingHistorySessionPushUids() }
        coVerify(exactly = 1) {
            repository.pushHistorySessionsToRemote(any(), "00000000-0000-4000-8000-000000000040", setOf(10L, 11L))
        }
        assertTrue(logs.any { it.contains("cycle=push outcome=ok") && it.contains("dirtySetMode=precise") })
    }

    @Test
    fun `110 login fresh tick bootstraps then runs full reconciliation push`() = runTest {
        val repository = mockk<InventoryRepository>()
        val logs = mutableListOf<String>()
        val owner = "00000000-0000-4000-8000-000000000110"
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(
                userId = owner,
                email = "user@example.test"
            )
        )
        coEvery { repository.bootstrapHistorySessionsFromRemote(any()) } returns Result.success(
            RemoteSessionBatchResult(
                inserted = 1,
                updated = 0,
                skipped = 0,
                failed = 0,
                unsupported = 0
            )
        )
        coEvery {
            repository.pushHistorySessionsToRemote(any(), owner, null)
        } returns Result.success(
            HistorySessionBackupPushSummary(
                uploaded = 2,
                skippedAlreadySynced = 0,
                attempted = 2
            )
        )
        val coordinator = HistorySessionPushCoordinator(
            repository = repository,
            remote = FakeConfiguredSessionRemote040(),
            authFlow = auth,
            flightOwner = SessionCloudSessionFlightOwner(logger = logs::add),
            scope = backgroundScope,
            debounceMs = 1L,
            logger = logs::add
        )

        coordinator.runPushCycle("login_fresh_tick")

        coVerify(exactly = 1) { repository.bootstrapHistorySessionsFromRemote(any()) }
        coVerify(exactly = 1) { repository.pushHistorySessionsToRemote(any(), owner, null) }
        coVerify(exactly = 0) { repository.getPendingHistorySessionPushUids() }
        assertTrue(
            logs.any {
                it.contains("cycle=push outcome=ok") &&
                    it.contains("dirtySetMode=full_reconcile") &&
                    it.contains("bootstrapInserted=1") &&
                    it.contains("sessionsUploaded=2")
            }
        )
    }

    @Test
    fun `040 signed out push cycle skips without querying repository`() = runTest {
        val repository = mockk<InventoryRepository>()
        val logs = mutableListOf<String>()
        val coordinator = HistorySessionPushCoordinator(
            repository = repository,
            remote = FakeConfiguredSessionRemote040(),
            authFlow = MutableStateFlow<AuthState>(AuthState.SignedOut),
            flightOwner = SessionCloudSessionFlightOwner(logger = logs::add),
            scope = backgroundScope,
            debounceMs = 1L,
            logger = logs::add
        )

        coordinator.runPushCycle("debounce_fired")

        coVerify(exactly = 0) { repository.getPendingHistorySessionPushUids() }
        assertTrue(logs.any { it.contains("reason=skipped_no_auth") })
    }

    @Test
    fun `040 failed push cycle logs classification and pending uid sample`() = runTest {
        val repository = mockk<InventoryRepository>()
        val logs = mutableListOf<String>()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(
                userId = "00000000-0000-4000-8000-000000000040",
                email = "user@example.test"
            )
        )
        coEvery { repository.getPendingHistorySessionPushUids() } returns listOf(40L)
        coEvery {
            repository.pushHistorySessionsToRemote(any(), "00000000-0000-4000-8000-000000000040", setOf(40L))
        } returns Result.failure(IllegalStateException("permission denied for table shared_sheet_sessions"))
        val coordinator = HistorySessionPushCoordinator(
            repository = repository,
            remote = FakeConfiguredSessionRemote040(),
            authFlow = auth,
            flightOwner = SessionCloudSessionFlightOwner(logger = logs::add),
            scope = backgroundScope,
            debounceMs = 1L,
            logger = logs::add
        )

        coordinator.runPushCycle("debounce_fired")

        assertTrue(
            logs.any {
                it.contains("cycle=push outcome=fail") &&
                    it.contains("errKind=RemoteForbiddenRls") &&
                    it.contains("pendingUidSample=40")
            }
        )
    }

    @Test
    fun `131 history push records targeted sync event without capability probe dependency`() = runTest {
        val repository = mockk<InventoryRepository>()
        val logs = mutableListOf<String>()
        val owner = "00000000-0000-4000-8000-000000000131"
        val syncEvents = CapturingSyncEventRemote131()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(
                userId = owner,
                email = "user@example.test"
            )
        )
        coEvery { repository.getPendingHistorySessionPushUids() } returns listOf(131L, 132L)
        coEvery {
            repository.pushHistorySessionsToRemote(any(), owner, setOf(131L, 132L))
        } returns Result.success(
            HistorySessionBackupPushSummary(
                uploaded = 2,
                skippedAlreadySynced = 0,
                attempted = 2,
                remoteIds = listOf(" SESSION-A ", "SESSION-B")
            )
        )
        val coordinator = HistorySessionPushCoordinator(
            repository = repository,
            remote = FakeConfiguredSessionRemote040(),
            syncEventRemote = syncEvents,
            authFlow = auth,
            flightOwner = SessionCloudSessionFlightOwner(logger = logs::add),
            scope = backgroundScope,
            debounceMs = 1L,
            logger = logs::add
        )

        coordinator.runPushCycle("debounce_fired")

        assertEquals(1, syncEvents.recorded.size)
        val event = syncEvents.recorded.single()
        assertEquals(SyncEventDomains.HISTORY, event.domain)
        assertEquals(SyncEventTypes.HISTORY_CHANGED, event.eventType)
        assertEquals(2, event.changedCount)
        assertEquals(listOf("session-a", "session-b"), event.entityIds?.sessionIds)
        assertTrue(logs.any { it.contains("cycle=push syncEvent=history outcome=ok") })
    }

    @Test
    fun `131 history sync event failure enqueues durable outbox`() = runTest {
        val repository = mockk<InventoryRepository>()
        val outbox = mockk<SyncEventOutboxDao>()
        val logs = mutableListOf<String>()
        val owner = "00000000-0000-4000-8000-000000000231"
        val syncEvents = FailingSyncEventRemote131()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(
                userId = owner,
                email = "user@example.test"
            )
        )
        coEvery { repository.getPendingHistorySessionPushUids() } returns listOf(231L)
        coEvery {
            repository.pushHistorySessionsToRemote(any(), owner, setOf(231L))
        } returns Result.success(
            HistorySessionBackupPushSummary(
                uploaded = 1,
                skippedAlreadySynced = 0,
                attempted = 1,
                remoteIds = listOf(" SESSION-A ")
            )
        )
        coEvery { outbox.insert(any()) } returns 7L
        val coordinator = HistorySessionPushCoordinator(
            repository = repository,
            remote = FakeConfiguredSessionRemote040(),
            syncEventRemote = syncEvents,
            syncEventOutboxDao = outbox,
            authFlow = auth,
            flightOwner = SessionCloudSessionFlightOwner(logger = logs::add),
            scope = backgroundScope,
            debounceMs = 1L,
            logger = logs::add
        )

        coordinator.runPushCycle("debounce_fired")

        assertEquals(3, syncEvents.attempts)
        coVerify(exactly = 1) {
            outbox.insert(
                match {
                    it.ownerUserId == owner &&
                        it.domain == SyncEventDomains.HISTORY &&
                        it.eventType == SyncEventTypes.HISTORY_CHANGED &&
                        it.changedCount == 1 &&
                        it.entityIdsJson.contains("session-a") &&
                        it.lastErrorType != null
                }
            )
        }
        assertTrue(
            logs.any {
                it.contains("cycle=push syncEvent=history outcome=enqueued") &&
                    it.contains("attempts=3") &&
                    it.contains("outboxInserted=1")
            }
        )
    }
}

private class FakeConfiguredSessionRemote040 : SessionBackupRemoteDataSource {
    override val isConfigured: Boolean = true

    override suspend fun fetchAllSessionsForOwner(): Result<List<SharedSheetSessionRecord>> =
        Result.success(emptyList())

    override suspend fun fetchSessionsByRemoteIds(remoteIds: Set<String>): Result<List<SharedSheetSessionRecord>> =
        Result.success(emptyList())

    override suspend fun upsertSessions(rows: List<SharedSheetSessionUpsertRow>): Result<Unit> =
        Result.success(Unit)
}

private class CapturingSyncEventRemote131 : SyncEventRemoteDataSource {
    override val isConfigured: Boolean = true
    val recorded = mutableListOf<SyncEventRecordRpcParams>()

    override suspend fun checkCapabilities(ownerUserId: String): Result<SyncEventRemoteCapabilities> =
        Result.failure(AssertionError("TASK-131 history event recording must not depend on capability probe"))

    override suspend fun recordSyncEvent(params: SyncEventRecordRpcParams): Result<SyncEventRemoteRow> {
        recorded += params
        return Result.success(
            SyncEventRemoteRow(
                id = recorded.size.toLong(),
                ownerUserId = "00000000-0000-4000-8000-000000000131",
                domain = params.domain,
                eventType = params.eventType,
                source = params.source,
                batchId = params.batchId,
                clientEventId = params.clientEventId,
                changedCount = params.changedCount,
                entityIds = params.entityIds,
                createdAt = "2026-05-28T00:00:00Z"
            )
        )
    }

    override suspend fun fetchSyncEventsAfter(
        ownerUserId: String,
        storeId: String?,
        afterId: Long,
        limit: Long
    ): Result<List<SyncEventRemoteRow>> = Result.success(emptyList())
}

private class FailingSyncEventRemote131 : SyncEventRemoteDataSource {
    override val isConfigured: Boolean = true
    var attempts = 0

    override suspend fun checkCapabilities(ownerUserId: String): Result<SyncEventRemoteCapabilities> =
        Result.failure(AssertionError("TASK-131 history event recording must not depend on capability probe"))

    override suspend fun recordSyncEvent(params: SyncEventRecordRpcParams): Result<SyncEventRemoteRow> {
        attempts++
        return Result.failure(IllegalStateException("transient sync event failure"))
    }

    override suspend fun fetchSyncEventsAfter(
        ownerUserId: String,
        storeId: String?,
        afterId: Long,
        limit: Long
    ): Result<List<SyncEventRemoteRow>> = Result.success(emptyList())
}
