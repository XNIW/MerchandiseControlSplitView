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
        assertEquals(2_000L, HistorySessionPushCoordinator.DEBOUNCE_MS)
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
