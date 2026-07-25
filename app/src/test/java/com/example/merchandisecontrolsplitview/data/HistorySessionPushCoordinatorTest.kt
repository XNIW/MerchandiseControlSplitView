package com.example.merchandisecontrolsplitview.data

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
    fun `132 login fresh tick bootstraps but skips history push when no pending sessions`() = runTest {
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
        coEvery { repository.getPendingHistorySessionPushUids() } returns emptyList()
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
        coVerify(exactly = 1) { repository.getPendingHistorySessionPushUids() }
        coVerify(exactly = 0) { repository.pushHistorySessionsToRemote(any(), any(), any()) }
        assertTrue(
            logs.any {
                it.contains("cycle=push outcome=ok") &&
                    it.contains("dirtySetMode=full_reconcile") &&
                    it.contains("bootstrapInserted=1") &&
                    it.contains("sessionsUploaded=0")
            }
        )
    }

    @Test
    fun `132 login fresh tick pushes only pending history sessions after bootstrap`() = runTest {
        val repository = mockk<InventoryRepository>()
        val logs = mutableListOf<String>()
        val owner = "00000000-0000-4000-8000-000000000132"
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(
                userId = owner,
                email = "user@example.test"
            )
        )
        coEvery { repository.bootstrapHistorySessionsFromRemote(any()) } returns Result.success(
            RemoteSessionBatchResult(
                inserted = 0,
                updated = 1,
                skipped = 0,
                failed = 0,
                unsupported = 0
            )
        )
        coEvery { repository.getPendingHistorySessionPushUids() } returns listOf(132L)
        coEvery {
            repository.pushHistorySessionsToRemote(any(), owner, setOf(132L))
        } returns Result.success(
            HistorySessionBackupPushSummary(
                uploaded = 1,
                skippedAlreadySynced = 0,
                attempted = 1
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
        coVerify(exactly = 1) { repository.getPendingHistorySessionPushUids() }
        coVerify(exactly = 1) { repository.pushHistorySessionsToRemote(any(), owner, setOf(132L)) }
        coVerify(exactly = 0) { repository.pushHistorySessionsToRemote(any(), owner, null) }
        assertTrue(
            logs.any {
                it.contains("cycle=push outcome=ok") &&
                    it.contains("dirtySetMode=full_reconcile") &&
                    it.contains("sessionsUploaded=1")
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
    fun `139 checking business scope blocks history repository and remote`() = runTest {
        assertBusinessScopeBlocksHistoryPush(
            state = Task126BusinessDataScopeState.checking(),
            expectedStatus = Task126BusinessDataScopeStatus.CHECKING
        )
    }

    @Test
    fun `139 unbound review blocks history repository and remote`() = runTest {
        assertBusinessScopeBlocksHistoryPush(
            state = Task126BusinessDataScopeState(
                status = Task126BusinessDataScopeStatus.REVIEW_REQUIRED_UNBOUND,
                localSnapshot = LocalDatabaseStatusSnapshot(
                    products = 1,
                    suppliers = 0,
                    categories = 0,
                    priceHistoryRows = 1,
                    historySessions = 1,
                    pendingLocalChanges = 1,
                    syncEventOutboxPending = 1
                )
            ),
            expectedStatus = Task126BusinessDataScopeStatus.REVIEW_REQUIRED_UNBOUND
        )
    }

    @Test
    fun `139 owner mismatch state and bound scope gate block history repository and remote`() = runTest {
        val shop = selectedShop("00000000-0000-4000-8000-000000000739")
        val differentOwner = "00000000-0000-4000-8000-000000000938"
        val mismatchedScope = task126ActiveOwnerStoreScope(differentOwner, shop)
        assertBusinessScopeBlocksHistoryPush(
            state = Task126BusinessDataScopeState(
                status = Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH,
                boundScope = mismatchedScope
            ),
            expectedStatus = Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH,
            shop = shop
        )
        assertBusinessScopeBlocksHistoryPush(
            state = Task126BusinessDataScopeState.ready(mismatchedScope),
            expectedStatus = Task126BusinessDataScopeStatus.READY,
            shop = shop
        )
    }

    @Test
    fun `139 shop mismatch state and bound scope gate block history repository and remote`() = runTest {
        val boundShop = selectedShop("00000000-0000-4000-8000-000000000738")
        val activeShop = selectedShop("00000000-0000-4000-8000-000000000739")
        val mismatchedScope = task126ActiveOwnerStoreScope(HISTORY_SCOPE_OWNER_139, boundShop)
        assertBusinessScopeBlocksHistoryPush(
            state = Task126BusinessDataScopeState(
                status = Task126BusinessDataScopeStatus.BLOCKED_SHOP_MISMATCH,
                boundScope = mismatchedScope
            ),
            expectedStatus = Task126BusinessDataScopeStatus.BLOCKED_SHOP_MISMATCH,
            shop = activeShop
        )
        assertBusinessScopeBlocksHistoryPush(
            state = Task126BusinessDataScopeState.ready(mismatchedScope),
            expectedStatus = Task126BusinessDataScopeStatus.READY,
            shop = activeShop
        )
    }

    @Test
    fun `139 same owner and shop binding allows history push`() = runTest {
        val repository = mockk<InventoryRepository>()
        val remote = mockk<SessionBackupRemoteDataSource>()
        val logs = mutableListOf<String>()
        val shop = selectedShop("00000000-0000-4000-8000-000000000739")
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(
                userId = HISTORY_SCOPE_OWNER_139,
                email = "user@example.test"
            )
        )
        every { remote.isConfigured } returns true
        coEvery { repository.getPendingHistorySessionPushUids() } returns listOf(139L)
        coEvery {
            repository.pushHistorySessionsToRemote(
                remote,
                HISTORY_SCOPE_OWNER_139,
                setOf(139L),
                shop
            )
        } returns Result.success(
            HistorySessionBackupPushSummary(
                uploaded = 1,
                skippedAlreadySynced = 0,
                attempted = 1
            )
        )
        val coordinator = HistorySessionPushCoordinator(
            repository = repository,
            remote = remote,
            authFlow = auth,
            selectedShopProvider = { shop },
            flightOwner = SessionCloudSessionFlightOwner(logger = logs::add),
            syncStateTracker = CatalogSyncStateTracker(
                Task126BusinessDataScopeState.ready(
                    task126ActiveOwnerStoreScope(HISTORY_SCOPE_OWNER_139, shop)
                )
            ),
            scope = backgroundScope,
            debounceMs = 1L,
            logger = logs::add
        )

        coordinator.runPushCycle("debounce_fired")

        verify(exactly = 1) { remote.isConfigured }
        coVerify(exactly = 1) { repository.getPendingHistorySessionPushUids() }
        coVerify(exactly = 1) {
            repository.pushHistorySessionsToRemote(
                remote,
                HISTORY_SCOPE_OWNER_139,
                setOf(139L),
                shop
            )
        }
        assertTrue(logs.any { it.contains("cycle=push outcome=ok") })
        assertTrue(logs.none { it.contains("reason=business_scope_blocked") })
    }

    @Test
    fun `139 scope change while waiting for session flight performs zero history calls`() = runTest {
        val repository = mockk<InventoryRepository>()
        val remote = mockk<SessionBackupRemoteDataSource>()
        val shop = selectedShop("00000000-0000-4000-8000-000000000739")
        var currentShop = shop
        val tracker = CatalogSyncStateTracker(
            Task126BusinessDataScopeState.ready(
                task126ActiveOwnerStoreScope(HISTORY_SCOPE_OWNER_139, shop)
            )
        )
        val flightOwner = SessionCloudSessionFlightOwner()
        every { remote.isConfigured } returns true
        val holderEntered = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()
        val holder = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            flightOwner.withSessionFlight(SessionCloudFlightOwner.Refresh) {
                holderEntered.complete(Unit)
                releaseHolder.await()
            }
        }
        holderEntered.await()
        val coordinator = HistorySessionPushCoordinator(
            repository = repository,
            remote = remote,
            authFlow = MutableStateFlow<AuthState>(
                AuthState.SignedIn(HISTORY_SCOPE_OWNER_139, "user@example.test")
            ),
            selectedShopProvider = { currentShop },
            flightOwner = flightOwner,
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 1L
        )

        val cycle = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.runPushCycle("debounce_fired")
        }
        currentShop = selectedShop("00000000-0000-4000-8000-000000000839")
        releaseHolder.complete(Unit)
        holder.await()
        cycle.await()

        verify(exactly = 1) { remote.isConfigured }
        coVerify(exactly = 0) { repository.getPendingHistorySessionPushUids() }
        coVerify(exactly = 0) {
            repository.pushHistorySessionsToRemote(any(), any(), any<Set<Long>>(), any())
        }
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
        val shop = selectedShop("00000000-0000-4000-8000-000000000731")
        val syncEvents = CapturingSyncEventRemote131()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(
                userId = owner,
                email = "user@example.test"
            )
        )
        coEvery { repository.getPendingHistorySessionPushUids() } returns listOf(131L, 132L)
        coEvery {
            repository.pushHistorySessionsToRemote(any(), owner, setOf(131L, 132L), shop)
        } returns Result.success(
            HistorySessionBackupPushSummary(
                uploaded = 2,
                skippedAlreadySynced = 0,
                attempted = 2,
                remoteIds = listOf(
                    " 00000000-0000-4000-8000-000000000131 ",
                    "00000000-0000-4000-8000-000000000132"
                )
            )
        )
        val coordinator = HistorySessionPushCoordinator(
            repository = repository,
            remote = FakeConfiguredSessionRemote040(),
            syncEventRemote = syncEvents,
            authFlow = auth,
            selectedShopProvider = { shop },
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
        assertEquals(
            listOf(
                "00000000-0000-4000-8000-000000000131",
                "00000000-0000-4000-8000-000000000132"
            ),
            event.entityIds?.sessionIds
        )
        assertEquals(shop.shopId, event.storeId)
        assertEquals(shop.shopId, event.shopId)
        assertTrue(logs.any { it.contains("cycle=push syncEvent=history outcome=ok") })
    }

    @Test
    fun `139 history push chunks complete events at the V6 25 session id cap`() = runTest {
        val repository = mockk<InventoryRepository>()
        val logs = mutableListOf<String>()
        val owner = "00000000-0000-4000-8000-000000000139"
        val shop = selectedShop("00000000-0000-4000-8000-000000000739")
        val syncEvents = CapturingSyncEventRemote131()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(userId = owner, email = "user@example.test")
        )
        val pendingUids = (1L..260L).toList()
        val remoteIds = pendingUids.map {
            "00000000-0000-4000-8000-${it.toString().padStart(12, '0')}"
        }
        coEvery { repository.getPendingHistorySessionPushUids() } returns pendingUids
        coEvery {
            repository.pushHistorySessionsToRemote(any(), owner, pendingUids.toSet(), shop)
        } returns Result.success(
            HistorySessionBackupPushSummary(
                uploaded = remoteIds.size,
                skippedAlreadySynced = 0,
                attempted = remoteIds.size,
                remoteIds = remoteIds
            )
        )
        val coordinator = HistorySessionPushCoordinator(
            repository = repository,
            remote = FakeConfiguredSessionRemote040(),
            syncEventRemote = syncEvents,
            authFlow = auth,
            selectedShopProvider = { shop },
            flightOwner = SessionCloudSessionFlightOwner(logger = logs::add),
            scope = backgroundScope,
            debounceMs = 1L,
            logger = logs::add
        )

        coordinator.runPushCycle("debounce_fired")

        assertEquals(
            List(10) { 25 } + listOf(10),
            syncEvents.recorded.map { it.changedCount }
        )
        assertEquals(
            List(10) { 25 } + listOf(10),
            syncEvents.recorded.map { it.entityIds!!.sessionIds.size }
        )
        assertEquals(260, syncEvents.recorded.flatMap { it.entityIds!!.sessionIds }.distinct().size)
        assertEquals(1, syncEvents.recorded.map { it.batchId }.distinct().size)
        assertEquals(11, syncEvents.recorded.map { it.clientEventId }.distinct().size)
        assertTrue(syncEvents.recorded.none { it.entityIds!!.isEmpty })
        assertTrue(
            logs.any {
                it.contains("cycle=push syncEvent=history outcome=ok") &&
                    it.contains("sessions=260") &&
                    it.contains("chunks=11") &&
                    it.contains("recordedChunks=11")
            }
        )
    }

    @Test
    fun `131 history sync event failure enqueues durable outbox`() = runTest {
        val repository = mockk<InventoryRepository>()
        val outbox = mockk<SyncEventOutboxDao>()
        val logs = mutableListOf<String>()
        val owner = "00000000-0000-4000-8000-000000000231"
        val shop = selectedShop("00000000-0000-4000-8000-000000000732")
        val syncEvents = FailingSyncEventRemote131()
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(
                userId = owner,
                email = "user@example.test"
            )
        )
        coEvery { repository.getPendingHistorySessionPushUids() } returns listOf(231L)
        coEvery {
            repository.pushHistorySessionsToRemote(any(), owner, setOf(231L), shop)
        } returns Result.success(
            HistorySessionBackupPushSummary(
                uploaded = 1,
                skippedAlreadySynced = 0,
                attempted = 1,
                remoteIds = listOf(" 00000000-0000-4000-8000-000000000231 ")
            )
        )
        coEvery { outbox.insert(any()) } returns 7L
        val coordinator = HistorySessionPushCoordinator(
            repository = repository,
            remote = FakeConfiguredSessionRemote040(),
            syncEventRemote = syncEvents,
            syncEventOutboxDao = outbox,
            authFlow = auth,
            selectedShopProvider = { shop },
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
                        it.storeScope == "shop:${shop.shopId}" &&
                        it.changedCount == 1 &&
                        it.entityIdsJson.contains("00000000-0000-4000-8000-000000000231") &&
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

    @Test
    fun `136 local history push retries after transient cancellation`() = runTest {
        val repository = mockk<InventoryRepository>()
        val logs = mutableListOf<String>()
        val owner = "00000000-0000-4000-8000-000000000136"
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(
                userId = owner,
                email = "user@example.test"
            )
        )
        var attempts = 0
        coEvery { repository.getPendingHistorySessionPushUids() } returns listOf(136L)
        coEvery {
            repository.pushHistorySessionsToRemote(any(), owner, setOf(136L))
        } coAnswers {
            attempts++
            if (attempts == 1) {
                Result.failure(CancellationException("transient device status refresh"))
            } else {
                Result.success(
                    HistorySessionBackupPushSummary(
                        uploaded = 1,
                        skippedAlreadySynced = 0,
                        attempted = 1,
                        remoteIds = listOf("SESSION-136")
                    )
                )
            }
        }
        val coordinator = HistorySessionPushCoordinator(
            repository = repository,
            remote = FakeConfiguredSessionRemote040(),
            authFlow = auth,
            flightOwner = SessionCloudSessionFlightOwner(logger = logs::add),
            scope = backgroundScope,
            debounceMs = 10_000L,
            logger = logs::add
        )

        coordinator.runPushCycle("local_commit")
        advanceTimeBy(HistorySessionPushCoordinator.RETRY_AFTER_BUSY_MS)
        runCurrent()

        assertEquals(2, attempts)
        assertTrue(logs.any { it.contains("cycle=push outcome=queued_after_retryable_cancellation") })
        assertTrue(
            logs.any {
                it.contains("cycle=push outcome=ok") &&
                    it.contains("reason=retry_after_busy_local_commit") &&
                    it.contains("sessionsUploaded=1")
            }
        )
    }

    @Test
    fun `136 local history push retries after retryable device status`() = runTest {
        val repository = mockk<InventoryRepository>()
        val logs = mutableListOf<String>()
        val owner = "00000000-0000-4000-8000-000000000236"
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(
                userId = owner,
                email = "user@example.test"
            )
        )
        var attempts = 0
        coEvery { repository.getPendingHistorySessionPushUids() } returns listOf(236L)
        coEvery {
            repository.pushHistorySessionsToRemote(any(), owner, setOf(236L))
        } coAnswers {
            attempts++
            Result.success(
                HistorySessionBackupPushSummary(
                    uploaded = 1,
                    skippedAlreadySynced = 0,
                    attempted = 1,
                    remoteIds = listOf("SESSION-236")
                )
            )
        }
        val deviceRemote = SequencedShopDeviceRegistrationRemote136(
            listOf(
                snapshot136(
                    status = "network_error",
                    code = "JobCancellationException",
                    canWrite = false,
                    reasonCode = "network_error",
                    recommendedAction = "retry_when_online"
                ),
                snapshot136(status = "active")
            )
        )
        val coordinator = HistorySessionPushCoordinator(
            repository = repository,
            remote = FakeConfiguredSessionRemote040(),
            deviceAuthorization = ShopDeviceAuthorizationRepository(deviceRemote, cacheTtlMs = 0L),
            authFlow = auth,
            flightOwner = SessionCloudSessionFlightOwner(logger = logs::add),
            scope = backgroundScope,
            debounceMs = 10_000L,
            logger = logs::add
        )

        coordinator.runPushCycle("local_commit")
        advanceTimeBy(HistorySessionPushCoordinator.RETRY_AFTER_BUSY_MS)
        runCurrent()

        assertEquals(1, attempts)
        assertTrue(logs.any { it.contains("cycle=push outcome=queued_after_device_status") })
        assertTrue(
            logs.any {
                it.contains("cycle=push outcome=ok") &&
                    it.contains("reason=retry_after_busy_local_commit") &&
                    it.contains("sessionsUploaded=1")
            }
        )
    }

    @Test
    fun `114 resume tick retries pending history after retryable device status`() = runTest {
        val repository = mockk<InventoryRepository>()
        val logs = mutableListOf<String>()
        val owner = "00000000-0000-4000-8000-000000000114"
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(
                userId = owner,
                email = "user@example.test"
            )
        )
        var attempts = 0
        coEvery { repository.getPendingHistorySessionPushUids() } returns listOf(114L)
        coEvery {
            repository.pushHistorySessionsToRemote(any(), owner, setOf(114L))
        } coAnswers {
            attempts++
            Result.success(
                HistorySessionBackupPushSummary(
                    uploaded = 1,
                    skippedAlreadySynced = 0,
                    attempted = 1,
                    remoteIds = listOf("SESSION-114")
                )
            )
        }
        val deviceRemote = SequencedShopDeviceRegistrationRemote136(
            listOf(
                snapshot136(
                    status = "network_error",
                    code = "JobCancellationException",
                    canWrite = false,
                    reasonCode = "network_error",
                    recommendedAction = "retry_when_online"
                ),
                snapshot136(status = "active")
            )
        )
        val coordinator = HistorySessionPushCoordinator(
            repository = repository,
            remote = FakeConfiguredSessionRemote040(),
            deviceAuthorization = ShopDeviceAuthorizationRepository(deviceRemote, cacheTtlMs = 0L),
            authFlow = auth,
            flightOwner = SessionCloudSessionFlightOwner(logger = logs::add),
            scope = backgroundScope,
            debounceMs = 10_000L,
            logger = logs::add
        )

        coordinator.onAppBackground()
        coordinator.onLocalHistorySessionChanged(114L)
        advanceTimeBy(HistorySessionPushCoordinator.RETRY_AFTER_BUSY_MS)
        runCurrent()
        coordinator.onAppForeground()
        coordinator.runPushCycle("resume_tick")
        advanceTimeBy(HistorySessionPushCoordinator.RETRY_AFTER_BUSY_MS)
        runCurrent()

        assertEquals(1, attempts)
        assertTrue(logs.any { it.contains("cycle=push outcome=queued_after_device_status") })
        assertTrue(
            logs.any {
                it.contains("cycle=push outcome=ok") &&
                    it.contains("reason=retry_after_busy_resume_tick") &&
                    it.contains("sessionsUploaded=1")
            }
        )
    }

    @Test
    fun `136 local history push suppresses persistent retryable device status loop`() = runTest {
        val repository = mockk<InventoryRepository>()
        val logs = mutableListOf<String>()
        val owner = "00000000-0000-4000-8000-000000000336"
        val auth = MutableStateFlow<AuthState>(
            AuthState.SignedIn(
                userId = owner,
                email = "user@example.test"
            )
        )
        val deviceRemote = SequencedShopDeviceRegistrationRemote136(
            List(8) {
                snapshot136(
                    status = "network_error",
                    code = "JobCancellationException",
                    canWrite = false,
                    reasonCode = "network_error",
                    recommendedAction = "retry_when_online"
                )
            }
        )
        val coordinator = HistorySessionPushCoordinator(
            repository = repository,
            remote = FakeConfiguredSessionRemote040(),
            deviceAuthorization = ShopDeviceAuthorizationRepository(deviceRemote, cacheTtlMs = 0L),
            authFlow = auth,
            flightOwner = SessionCloudSessionFlightOwner(logger = logs::add),
            scope = backgroundScope,
            debounceMs = 10_000L,
            logger = logs::add
        )

        coordinator.runPushCycle("local_commit")
        repeat(HistorySessionPushCoordinator.RETRY_AFTER_DEVICE_STATUS_MAX_ATTEMPTS + 2) {
            advanceTimeBy(HistorySessionPushCoordinator.RETRY_AFTER_DEVICE_STATUS_MAX_MS + 10L)
            runCurrent()
        }
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.getPendingHistorySessionPushUids() }
        assertTrue(logs.any { it.contains("cycle=push outcome=device_status_retry_suppressed") })
    }

    private suspend fun TestScope.assertBusinessScopeBlocksHistoryPush(
        state: Task126BusinessDataScopeState,
        expectedStatus: Task126BusinessDataScopeStatus,
        shop: SelectedShop = selectedShop("00000000-0000-4000-8000-000000000739")
    ) {
        val repository = mockk<InventoryRepository>()
        val remote = mockk<SessionBackupRemoteDataSource>()
        val logs = mutableListOf<String>()
        val coordinator = HistorySessionPushCoordinator(
            repository = repository,
            remote = remote,
            authFlow = MutableStateFlow<AuthState>(
                AuthState.SignedIn(
                    userId = HISTORY_SCOPE_OWNER_139,
                    email = "user@example.test"
                )
            ),
            selectedShopProvider = { shop },
            flightOwner = SessionCloudSessionFlightOwner(logger = logs::add),
            syncStateTracker = CatalogSyncStateTracker(state),
            scope = backgroundScope,
            debounceMs = 1L,
            logger = logs::add
        )

        coordinator.runPushCycle("debounce_fired")

        verify(exactly = 0) { remote.isConfigured }
        coVerify(exactly = 0) { repository.getPendingHistorySessionPushUids() }
        assertTrue(
            logs.any {
                it.contains("cycle=push outcome=skip") &&
                    it.contains("reason=business_scope_blocked") &&
                    it.contains("scopeStatus=$expectedStatus")
            }
        )
    }

    private companion object {
        const val HISTORY_SCOPE_OWNER_139 = "00000000-0000-4000-8000-000000000139"
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

private class SequencedShopDeviceRegistrationRemote136(
    snapshots: List<ShopDeviceAuthorizationSnapshot>
) : ShopDeviceRegistrationRemote {
    override val isConfigured: Boolean = true
    private val remaining = ArrayDeque(snapshots)

    override suspend fun registerCurrentOwnerDevice(reason: String): Result<ShopDeviceRegistrationResult> =
        Result.success(ShopDeviceRegistrationResult(ok = true, code = "success"))

    override suspend fun currentOwnerDeviceStatus(reason: String): Result<ShopDeviceAuthorizationSnapshot> =
        Result.success(remaining.removeFirstOrNull() ?: snapshot136(status = "active"))
}

private fun snapshot136(
    status: String,
    code: String = if (status == "active") "success" else status,
    canWrite: Boolean = status == "active",
    reasonCode: String = status,
    recommendedAction: String = if (status == "active") "allow" else "contact_shop_admin"
): ShopDeviceAuthorizationSnapshot =
    ShopDeviceAuthorizationSnapshot(
        status = status,
        code = code,
        canWrite = canWrite,
        serverTime = "2026-06-20T00:00:00Z",
        lastSeenAt = "2026-06-20T00:00:00Z",
        reasonCode = reasonCode,
        recommendedAction = recommendedAction,
        checkedAtMs = System.currentTimeMillis()
    )

private fun selectedShop(shopId: String): SelectedShop =
    SelectedShop(
        shopId = shopId,
        code = "SHOP",
        name = "Shop",
        role = "shop_owner",
        status = "active",
        canWrite = true
    )
