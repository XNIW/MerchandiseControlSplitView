package com.example.merchandisecontrolsplitview.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogAutoSyncCoordinatorTest {

    @Test
    fun `123 default catalog auto push debounce stays within warm autosync budget`() {
        assertEquals(500L, CatalogAutoSyncCoordinator.DEBOUNCE_MS)
        assertEquals(2_000L, CatalogAutoSyncCoordinator.FOREGROUND_SYNC_EVENT_INTERVAL_MS)
    }

    @Test
    fun `139 checking review and owner or shop mismatch block every automatic catalog path`() = runTest {
        val selectedShop = selectedShop(SHOP_ID)
        val activeScope = task126ActiveOwnerStoreScope(USER_ID, selectedShop)
        val blockedStates = listOf(
            Task126BusinessDataScopeState.checking(),
            Task126BusinessDataScopeState(
                status = Task126BusinessDataScopeStatus.REVIEW_REQUIRED_UNBOUND
            ),
            Task126BusinessDataScopeState.ready(
                Task126OwnerStoreScope(
                    ownerHash = "synthetic-other-owner-hash",
                    storeId = activeScope.storeId,
                    localStoreId = activeScope.localStoreId
                )
            ),
            Task126BusinessDataScopeState.ready(
                Task126OwnerStoreScope(
                    ownerHash = activeScope.ownerHash,
                    storeId = "shop:synthetic-other-shop",
                    localStoreId = null
                )
            )
        )

        blockedStates.forEach { blockedState ->
            val repository = FakeCatalogAutoSyncRepository043().apply {
                shouldBootstrap = false
                hasPendingWork = true
            }
            val tracker = CatalogSyncStateTracker(blockedState)
            val catalogRemote = FakeCatalogRemote043()
            val syncEventRemote = FakeSyncEventRemote043()
            val deviceRemote = FakeShopDeviceRegistrationRemote072(status = "active")
            val logs = mutableListOf<String>()
            val coordinator = CatalogAutoSyncCoordinator(
                repository = repository,
                remote = catalogRemote,
                priceRemote = FakePriceRemote043(),
                syncEventRemote = syncEventRemote,
                deviceAuthorization = ShopDeviceAuthorizationRepository(deviceRemote),
                authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
                selectedShopProvider = { selectedShop },
                syncStateTracker = tracker,
                scope = backgroundScope,
                debounceMs = Long.MAX_VALUE,
                logger = { logs += it }
            )

            coordinator.runBootstrapCycle("auth_signed_in")
            coordinator.runPushCycle("local_catalog_commit")
            coordinator.runSyncEventDrainCycle("foreground")

            assertEquals(0, repository.bootstrapCalls)
            assertEquals(0, repository.pushCalls)
            assertEquals(0, repository.quickWithEventsCalls)
            assertEquals(0, repository.drainCalls)
            assertEquals(0, deviceRemote.statusCalls)
            assertEquals(0, catalogRemote.configurationReads)
            assertEquals(0, syncEventRemote.configurationReads)
            assertEquals(3, logs.count { it.contains("reason=business_scope_blocked") })
            coordinator.shutdown()
        }
    }

    @Test
    fun `139 same owner and shop scope allows bootstrap push drain and device checks`() = runTest {
        val selectedShop = selectedShop(SHOP_ID)
        val activeScope = task126ActiveOwnerStoreScope(USER_ID, selectedShop)
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = true
            hasPendingWork = true
        }
        val tracker = CatalogSyncStateTracker(Task126BusinessDataScopeState.ready(activeScope))
        tracker.updateNetworkAvailability(true)
        val catalogRemote = FakeCatalogRemote043()
        val syncEventRemote = FakeSyncEventRemote043()
        val deviceRemote = FakeShopDeviceRegistrationRemote072(status = "active")
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = catalogRemote,
            priceRemote = FakePriceRemote043(),
            syncEventRemote = syncEventRemote,
            deviceAuthorization = ShopDeviceAuthorizationRepository(deviceRemote),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            selectedShopProvider = { selectedShop },
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = Long.MAX_VALUE
        )

        coordinator.runBootstrapCycle("auth_signed_in")
        repository.shouldBootstrap = false
        coordinator.runPushCycle("local_catalog_commit")
        coordinator.runSyncEventDrainCycle("foreground")

        assertEquals(1, repository.bootstrapCalls)
        assertEquals(0, repository.pushCalls)
        assertEquals(1, repository.quickWithEventsCalls)
        assertEquals(1, repository.drainCalls)
        assertEquals(3, deviceRemote.statusCalls)
        assertTrue(catalogRemote.configurationReads > 0)
        assertTrue(syncEventRemote.configurationReads > 0)
        coordinator.shutdown()
    }

    @Test
    fun `139 scope change during device check blocks catalog remote write`() = runTest {
        val selectedShop = selectedShop(SHOP_ID)
        val tracker = CatalogSyncStateTracker(
            Task126BusinessDataScopeState.ready(
                task126ActiveOwnerStoreScope(USER_ID, selectedShop)
            )
        )
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
            hasPendingWork = true
        }
        val deviceRemote = FakeShopDeviceRegistrationRemote072(status = "active").apply {
            onStatusCall = {
                tracker.updateBusinessDataScopeState(Task126BusinessDataScopeState.checking())
            }
        }
        val logs = mutableListOf<String>()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            syncEventRemote = FakeSyncEventRemote043(),
            deviceAuthorization = ShopDeviceAuthorizationRepository(deviceRemote),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            selectedShopProvider = { selectedShop },
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = Long.MAX_VALUE,
            logger = logs::add
        )

        coordinator.runPushCycle("local_catalog_commit")

        assertEquals(1, deviceRemote.statusCalls)
        assertEquals(0, repository.quickWithEventsCalls)
        assertTrue(logs.any { it.contains("business_scope_changed_during_device_check") })
        coordinator.shutdown()
    }

    @Test
    fun `132 automatic push is blocked by safety guard when signed in and foreground`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043()
        val tracker = CatalogSyncStateTracker()
        val logs = mutableListOf<String>()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = Long.MAX_VALUE,
            logger = { logs += it }
        )

        coordinator.runPushCycle("test")

        assertEquals(0, repository.pushCalls)
        assertEquals(0, repository.quickWithEventsCalls)
        assertEquals(0, repository.bootstrapCalls)
        assertEquals(false, tracker.isSyncing.value)
        assertTrue(logs.any { it.contains("automatic_push_safety_guard") })
        coordinator.shutdown()
    }

    @Test
    fun `072 revoked device blocks automatic local push before remote writes`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
            hasPendingWork = true
            clearPendingOnPush = true
        }
        val tracker = CatalogSyncStateTracker()
        val logs = mutableListOf<String>()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            deviceAuthorization = ShopDeviceAuthorizationRepository(
                FakeShopDeviceRegistrationRemote072(status = "revoked")
            ),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = Long.MAX_VALUE,
            logger = { logs += it }
        )

        coordinator.runPushCycle("local_commit")

        assertEquals(0, repository.pushCalls)
        assertEquals(0, repository.quickWithEventsCalls)
        assertEquals(CatalogSyncStage.DEVICE_STATUS, tracker.state.value.stage)
        assertEquals(CatalogSyncStatus.FAILED, tracker.state.value.status)
        assertTrue(logs.any { it.contains("blocked_by_device_status") && it.contains("status=revoked") })
        coordinator.shutdown()
    }

    @Test
    fun `136 local push retries after transient device status cancellation`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
            hasPendingWork = true
            clearPendingOnPush = true
        }
        val tracker = CatalogSyncStateTracker()
        val logs = mutableListOf<String>()
        val deviceRemote = FakeShopDeviceRegistrationRemote072(status = "active").apply {
            nextStatus = Result.failure(CancellationException("cancelled"))
        }
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            syncEventRemote = FakeSyncEventRemote043(),
            deviceAuthorization = ShopDeviceAuthorizationRepository(deviceRemote),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 1L,
            logger = { logs += it }
        )

        coordinator.runPushCycle("local_commit")

        assertEquals(0, repository.quickWithEventsCalls)
        assertTrue(logs.any { it.contains("blocked_by_device_status") && it.contains("status=network_error") })
        assertTrue(logs.any { it.contains("queued_after_device_status") })

        advanceTimeBy(CatalogAutoSyncCoordinator.RETRY_AFTER_BUSY_MS + 2L)
        advanceUntilIdle()

        assertEquals(1, repository.quickWithEventsCalls)
        coordinator.shutdown()
    }

    @Test
    fun `136 local push suppresses persistent retryable device status loop`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
            hasPendingWork = true
            clearPendingOnPush = true
        }
        val tracker = CatalogSyncStateTracker()
        val logs = mutableListOf<String>()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            syncEventRemote = FakeSyncEventRemote043(),
            deviceAuthorization = ShopDeviceAuthorizationRepository(
                FakeShopDeviceRegistrationRemote072(status = "network_error")
            ),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 1L,
            logger = { logs += it }
        )

        coordinator.runPushCycle("local_commit")
        repeat(CatalogAutoSyncCoordinator.RETRY_AFTER_DEVICE_STATUS_MAX_ATTEMPTS + 2) {
            advanceTimeBy(CatalogAutoSyncCoordinator.RETRY_AFTER_DEVICE_STATUS_MAX_MS + 10L)
            runCurrent()
        }

        assertEquals(0, repository.quickWithEventsCalls)
        assertTrue(logs.any { it.contains("cycle=catalog_push outcome=device_status_retry_suppressed") })
        coordinator.shutdown()
    }

    @Test
    fun `136 local push retries after guarded write cancellation inside push`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
            hasPendingWork = true
            failQuickOnce = CancellationException("cancelled")
            clearPendingOnPush = true
        }
        val tracker = CatalogSyncStateTracker()
        val logs = mutableListOf<String>()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            syncEventRemote = FakeSyncEventRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 1L,
            logger = { logs += it }
        )

        coordinator.runPushCycle("local_commit")

        assertEquals(1, repository.quickWithEventsCalls)
        assertTrue(logs.any { it.contains("queued_after_retryable_cancellation") })

        advanceTimeBy(CatalogAutoSyncCoordinator.RETRY_AFTER_BUSY_MS + 2L)
        advanceUntilIdle()

        assertEquals(2, repository.quickWithEventsCalls)
        coordinator.shutdown()
    }

    @Test
    fun `043 auto push skips while a manual catalog flight owns the tracker`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043()
        val tracker = CatalogSyncStateTracker()
        tracker.updateNetworkAvailability(true)
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = Long.MAX_VALUE
        )

        tracker.tryBegin(CatalogSyncFlightOwner.MANUAL)
        coordinator.runPushCycle("manual_busy")

        assertEquals(0, repository.pushCalls)
        assertEquals(null, tracker.lastOutcome.value)
        tracker.finish(CatalogSyncFlightOwner.MANUAL)
        coordinator.shutdown()
    }

    @Test
    fun `132 local push waits while sync event drain owns tracker then retries`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
        }
        val tracker = CatalogSyncStateTracker()
        val logs = mutableListOf<String>()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            syncEventRemote = FakeSyncEventRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            selectedShopProvider = { selectedShop(SHOP_ID) },
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 1L,
            logger = { logs += it }
        )

        assertTrue(tracker.tryBegin(CatalogSyncFlightOwner.SYNC_EVENTS))
        coordinator.runPushCycle("local_commit")

        assertEquals(0, repository.quickWithEventsCalls)
        assertTrue(logs.any { it.contains("cycle=catalog_push outcome=skip reason=sync_busy") })

        tracker.finish(CatalogSyncFlightOwner.SYNC_EVENTS)
        advanceTimeBy(CatalogAutoSyncCoordinator.RETRY_AFTER_BUSY_MS + 2L)
        advanceUntilIdle()

        assertEquals(1, repository.quickWithEventsCalls)
        coordinator.shutdown()
    }

    @Test
    fun `136 generic local catalog change waits while sync event drain owns tracker then retries`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
            clearPendingOnPush = true
        }
        val tracker = CatalogSyncStateTracker()
        tracker.updateNetworkAvailability(true)
        val logs = mutableListOf<String>()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            syncEventRemote = FakeSyncEventRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            selectedShopProvider = { selectedShop(SHOP_ID) },
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 1L,
            logger = { logs += it }
        )

        assertTrue(tracker.tryBegin(CatalogSyncFlightOwner.SYNC_EVENTS))
        coordinator.onLocalCatalogChanged()
        coordinator.runPushCycle("local_catalog_commit")

        assertEquals(0, repository.quickWithEventsCalls)
        assertTrue(logs.any { it.contains("cycle=catalog_push outcome=skip reason=sync_busy") })

        tracker.finish(CatalogSyncFlightOwner.SYNC_EVENTS)
        advanceTimeBy(CatalogAutoSyncCoordinator.RETRY_AFTER_BUSY_MS + 2L)
        advanceUntilIdle()

        assertEquals(1, repository.quickWithEventsCalls)
        coordinator.shutdown()
    }

    @Test
    fun `132D local dirty push schedules final sync event drain`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
        }
        val tracker = CatalogSyncStateTracker()
        tracker.updateNetworkAvailability(true)
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            syncEventRemote = FakeSyncEventRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            selectedShopProvider = { selectedShop(SHOP_ID) },
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 1L
        )
        runCurrent()

        coordinator.runPushCycle("local_commit")
        advanceTimeBy(2L)
        advanceUntilIdle()

        assertEquals(1, repository.quickWithEventsCalls)
        assertEquals(1, repository.drainCalls)
        coordinator.shutdown()
    }

    @Test
    fun `132 sync event drain yields to catalog bootstrap when bootstrap is required`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = true
        }
        val tracker = CatalogSyncStateTracker()
        tracker.updateNetworkAvailability(true)
        val logs = mutableListOf<String>()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            syncEventRemote = FakeSyncEventRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            selectedShopProvider = { selectedShop(SHOP_ID) },
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = Long.MAX_VALUE,
            logger = { logs += it }
        )

        coordinator.runSyncEventDrainCycle("foreground")

        assertEquals(0, repository.drainCalls)
        assertEquals(false, tracker.isSyncing.value)
        assertTrue(logs.any { it.contains("cycle=sync_events_drain outcome=skip reason=bootstrap_required") })
        coordinator.shutdown()
    }

    @Test
    fun `132 auto push with sync events is blocked before repository calls`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            nextQuickSummary = emptySummary(pushedProducts = 1).copy(
                manualFullSyncRequired = true,
                syncEventsGapDetected = true,
                syncEventsTooLarge = true,
                syncEventOutboxRetried = 1,
                syncEventOutboxPending = 2
            )
        }
        val tracker = CatalogSyncStateTracker()
        tracker.updateNetworkAvailability(true)
        val logs = mutableListOf<String>()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            syncEventRemote = FakeSyncEventRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            selectedShopProvider = { selectedShop(SHOP_ID) },
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = Long.MAX_VALUE,
            logger = { logs += it }
        )

        coordinator.runPushCycle("sync_events")

        assertEquals(0, repository.pushCalls)
        assertEquals(0, repository.quickWithEventsCalls)
        assertEquals(null, tracker.lastOutcome.value?.source)
        assertTrue(logs.any { it.contains("automatic_push_safety_guard") })
        coordinator.shutdown()
    }

    @Test
    fun `139 quick push gap closes scope and does not enqueue another drain`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
            hasPendingWork = true
            nextQuickSummary = emptySummary(pushedProducts = 1).copy(
                manualFullSyncRequired = true,
                syncEventsGapDetected = true
            )
        }
        val tracker = CatalogSyncStateTracker()
        tracker.updateNetworkAvailability(true)
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            syncEventRemote = FakeSyncEventRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            selectedShopProvider = { selectedShop(SHOP_ID) },
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 1L
        )

        coordinator.runPushCycle("local_commit")
        advanceTimeBy(2L)
        advanceUntilIdle()

        assertEquals(1, repository.quickWithEventsCalls)
        assertEquals(0, repository.drainCalls)
        assertEquals(
            Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
            tracker.businessDataScopeState.value.status
        )
        assertEquals(CatalogSyncStatus.FAILED, tracker.state.value.status)
        coordinator.shutdown()
    }

    @Test
    fun `061 sync event drain publishes summary outcome`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
            nextDrainSummary = emptySummary(pulledProducts = 1).copy(
                manualFullSyncRequired = true,
                syncEventsFetched = 3,
                syncEventsProcessed = 2,
                syncEventsGapDetected = true,
                syncEventOutboxRetried = 1,
                syncEventOutboxPending = 1
            )
        }
        val tracker = CatalogSyncStateTracker()
        tracker.updateNetworkAvailability(true)
        val logs = mutableListOf<String>()
        val recoveryTriggers = mutableListOf<String>()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            syncEventRemote = FakeSyncEventRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            selectedShopProvider = { selectedShop(SHOP_ID) },
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = Long.MAX_VALUE,
            onRecoveryRequired = { recoveryTriggers += it },
            logger = { logs += it }
        )

        coordinator.runSyncEventDrainCycle("test")

        assertEquals(1, repository.drainCalls)
        assertEquals(CatalogSyncFlightOwner.SYNC_EVENTS, tracker.lastOutcome.value?.source)
        assertEquals(USER_ID, tracker.lastOutcome.value?.ownerUserId)
        assertEquals(true, tracker.lastOutcome.value?.summary?.manualFullSyncRequired)
        assertTrue(logs.any { it.contains("manualFullSyncRequired=true") })
        assertTrue(logs.any { it.contains("syncEventsGapDetected=true") })
        assertTrue(logs.any { it.contains("syncEventOutboxRetried=1") })
        assertTrue(logs.any { it.contains("outboxPending=1") })
        assertEquals(
            Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
            tracker.businessDataScopeState.value.status
        )
        assertEquals("sync_recovery_required", tracker.businessDataScopeState.value.errorCode)
        assertEquals(listOf("sync_events_drain"), recoveryTriggers)
        assertEquals(CatalogSyncStatus.FAILED, tracker.state.value.status)
        assertTrue(logs.any { it.contains("outcome=blocked_recovery_required") })
        coordinator.shutdown()
    }

    @Test
    fun `139 dirty local event blocks completion without forcing destructive recovery`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
            nextDrainSummary = emptySummary().copy(syncEventsSkippedDirtyLocal = 1)
        }
        val tracker = CatalogSyncStateTracker()
        tracker.updateNetworkAvailability(true)
        val logs = mutableListOf<String>()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            syncEventRemote = FakeSyncEventRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            selectedShopProvider = { selectedShop(SHOP_ID) },
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = Long.MAX_VALUE,
            logger = { logs += it }
        )

        coordinator.runSyncEventDrainCycle("dirty_local")

        assertEquals(CatalogSyncStatus.FAILED, tracker.state.value.status)
        assertEquals(Task126BusinessDataScopeStatus.UNMANAGED_ALLOWED, tracker.businessDataScopeState.value.status)
        assertTrue(logs.any { it.contains("outcome=blocked_dirty_local") })
        coordinator.shutdown()
    }

    @Test
    fun `132D foreground runs pull only reconcile when local baseline is already usable`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
            hasPendingWork = false
        }
        val tracker = CatalogSyncStateTracker()
        val logs = mutableListOf<String>()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            syncEventRemote = FakeSyncEventRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = Long.MAX_VALUE,
            logger = { logs += it }
        )

        coordinator.runBootstrapCycle("foreground")

        assertEquals(1, repository.bootstrapCalls)
        assertEquals(0, repository.pushCalls)
        assertEquals(0, repository.quickWithEventsCalls)
        assertEquals(CatalogSyncFlightOwner.BOOTSTRAP, tracker.lastOutcome.value?.source)
        assertEquals(USER_ID, tracker.lastOutcome.value?.ownerUserId)
        assertTrue(logs.any { it.contains("bootstrapRequired=false") && it.contains("pullOnly=true") })
        coordinator.shutdown()
    }

    @Test
    fun `139 sync event gap persists fail closed without ordinary bootstrap loop`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
            nextDrainSummary = emptySummary().copy(manualFullSyncRequired = true)
        }
        val tracker = CatalogSyncStateTracker()
        tracker.updateNetworkAvailability(true)
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            syncEventRemote = FakeSyncEventRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            selectedShopProvider = { selectedShop(SHOP_ID) },
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 1L
        )

        runCurrent()
        coordinator.runSyncEventDrainCycle("test")
        advanceTimeBy(2L)
        advanceUntilIdle()

        assertEquals(1, repository.drainCalls)
        assertEquals(0, repository.bootstrapCalls)
        assertEquals(
            Task126BusinessDataScopeStatus.ERROR_RECOVERABLE,
            tracker.businessDataScopeState.value.status
        )

        coordinator.onNetworkAvailable()
        coordinator.onAppForeground()
        advanceTimeBy(2L)
        advanceUntilIdle()

        assertEquals(1, repository.drainCalls)
        assertEquals(0, repository.bootstrapCalls)
        coordinator.shutdown()
    }

    @Test
    fun `139 bounded rpc poll runs near two second cadence and stops in background`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
        }
        val tracker = CatalogSyncStateTracker()
        tracker.updateNetworkAvailability(true)
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            syncEventRemote = FakeSyncEventRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            selectedShopProvider = { selectedShop(SHOP_ID) },
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 1L,
            foregroundSyncEventIntervalMs = 10L
        )

        runCurrent()
        advanceTimeBy(2L)
        advanceUntilIdle()
        repository.drainCalls = 0

        coordinator.onAppForeground()
        advanceTimeBy(35L)
        runCurrent()

        assertTrue(repository.drainCalls in 3..5)
        coordinator.onAppBackground()
        val callsAtBackground = repository.drainCalls
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(callsAtBackground, repository.drainCalls)
        coordinator.shutdown()
    }

    @Test
    fun `139 bounded rpc poll requires verified network and resolved shop`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply { shouldBootstrap = false }
        val tracker = CatalogSyncStateTracker()
        tracker.updateNetworkAvailability(false)
        var currentShop: SelectedShop? = null
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            syncEventRemote = FakeSyncEventRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            selectedShopProvider = { currentShop },
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 1L,
            foregroundSyncEventIntervalMs = 10L
        )

        runCurrent()
        coordinator.onAppForeground()
        advanceTimeBy(25L)
        runCurrent()
        assertEquals(0, repository.drainCalls)

        currentShop = selectedShop(SHOP_ID)
        advanceTimeBy(25L)
        runCurrent()
        assertEquals(0, repository.drainCalls)

        tracker.updateNetworkAvailability(true)
        coordinator.onNetworkAvailable()
        advanceTimeBy(2L)
        runCurrent()
        assertEquals(1, repository.drainCalls)
        coordinator.shutdown()
    }

    @Test
    fun `139 bounded rpc poll backs off after transport failures`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
            failDrain = IllegalStateException("fixture_transport_failure")
        }
        val tracker = CatalogSyncStateTracker()
        tracker.updateNetworkAvailability(true)
        val logs = mutableListOf<String>()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            syncEventRemote = FakeSyncEventRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            selectedShopProvider = { selectedShop(SHOP_ID) },
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 1L,
            foregroundSyncEventIntervalMs = 10L,
            foregroundSyncEventMaxBackoffMs = 40L,
            logger = { logs += it }
        )

        runCurrent()
        coordinator.onAppForeground()
        advanceTimeBy(35L)
        runCurrent()

        assertTrue(repository.drainCalls in 2..3)
        assertTrue(logs.any { it.contains("nextPollMs=20") })
        assertTrue(logs.any { it.contains("nextPollMs=40") })
        coordinator.shutdown()
    }

    @Test
    fun `139 automatic login scope pulls before pending local catalog push`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
            hasPendingWork = true
            clearPendingOnPush = true
        }
        val tracker = CatalogSyncStateTracker()
        val logs = mutableListOf<String>()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            syncEventRemote = FakeSyncEventRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 1L,
            logger = { logs += it }
        )

        coordinator.onLocalProductChanged(42L)
        coordinator.runBootstrapCycle("device_active_direct")

        assertEquals(1, repository.bootstrapCalls)
        assertEquals(0, repository.quickWithEventsCalls)
        assertTrue(logs.any { it.contains("cycle=catalog_bootstrap outcome=ok") })

        advanceTimeBy(CatalogAutoSyncCoordinator.RETRY_AFTER_BUSY_MS + 2L)
        advanceUntilIdle()

        assertEquals(1, repository.quickWithEventsCalls)
        coordinator.shutdown()
    }

    @Test
    fun `136 automatic bootstrap defers during local push quiet window`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
            hasPendingWork = true
            clearPendingOnPush = true
        }
        val tracker = CatalogSyncStateTracker()
        val logs = mutableListOf<String>()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            syncEventRemote = FakeSyncEventRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 10_000L,
            logger = { logs += it }
        )

        coordinator.runPushCycle("local_catalog_commit")
        coordinator.runBootstrapCycle("auth_signed_in_retry_after_busy")

        assertEquals(1, repository.quickWithEventsCalls)
        assertEquals(0, repository.bootstrapCalls)
        assertTrue(logs.any { it.contains("deferred_after_recent_local_push") })
        coordinator.shutdown()
    }

    @Test
    fun `132 guarded auto push failure path does not touch repository or publish outcome`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            failQuick = IllegalStateException("quick failed")
        }
        val tracker = CatalogSyncStateTracker()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            syncEventRemote = FakeSyncEventRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = Long.MAX_VALUE
        )

        coordinator.runPushCycle("failure")

        assertEquals(0, repository.quickWithEventsCalls)
        assertEquals(null, tracker.lastOutcome.value)
        assertEquals(false, tracker.isSyncing.value)
        coordinator.shutdown()
    }

    @Test
    fun `043 bootstrap pull is process scoped by user and retry guard`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043()
        val tracker = CatalogSyncStateTracker()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = Long.MAX_VALUE,
            bootstrapRetryGuardMs = Long.MAX_VALUE
        )

        coordinator.runBootstrapCycle("first")
        coordinator.runBootstrapCycle("second")

        assertEquals(1, repository.bootstrapCalls)
        assertEquals(0, repository.pushCalls)
        coordinator.shutdown()
    }

    @Test
    fun `132 network available does not schedule automatic push while guard is active`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043()
        val tracker = CatalogSyncStateTracker()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 1L,
            bootstrapRetryGuardMs = Long.MAX_VALUE
        )
        runCurrent()
        advanceTimeBy(2L)
        advanceUntilIdle()
        repository.pushCalls = 0
        repository.bootstrapCalls = 0

        coordinator.onNetworkAvailable()
        advanceTimeBy(2L)
        advanceUntilIdle()

        assertEquals(0, repository.pushCalls)
        assertTrue(repository.bootstrapCalls in 0..1)
        assertEquals(false, tracker.isSyncing.value)
        coordinator.shutdown()
    }

    @Test
    fun `132 generic local catalog change pushes when baseline is usable and pending work exists`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
        }
        val tracker = CatalogSyncStateTracker()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 1L
        )
        runCurrent()
        advanceTimeBy(2L)
        advanceUntilIdle()
        repository.pushCalls = 0

        coordinator.onLocalCatalogChanged()
        advanceTimeBy(2L)
        advanceUntilIdle()

        assertEquals(1, repository.pushCalls)
        assertEquals(false, tracker.isSyncing.value)
        coordinator.shutdown()
    }

    @Test
    fun `132 local catalog change is blocked when bootstrap is required`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043()
        val tracker = CatalogSyncStateTracker()
        val logs = mutableListOf<String>()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 1L,
            logger = { logs += it }
        )
        runCurrent()
        advanceTimeBy(2L)
        advanceUntilIdle()
        repository.pushCalls = 0
        repository.bootstrapCalls = 0

        coordinator.onLocalCatalogChanged()
        advanceTimeBy(2L)
        advanceUntilIdle()

        assertEquals(0, repository.pushCalls)
        assertTrue(logs.any { it.contains("policy=bootstrap_required") })
        assertEquals(false, tracker.isSyncing.value)
        coordinator.shutdown()
    }

    @Test
    fun `132 local catalog change is skipped when no pending catalog work exists`() = runTest {
        val repository = FakeCatalogAutoSyncRepository043().apply {
            shouldBootstrap = false
            hasPendingWork = false
        }
        val tracker = CatalogSyncStateTracker()
        val logs = mutableListOf<String>()
        val coordinator = CatalogAutoSyncCoordinator(
            repository = repository,
            remote = FakeCatalogRemote043(),
            priceRemote = FakePriceRemote043(),
            authFlow = MutableStateFlow(AuthState.SignedIn(USER_ID, "user@example.test")),
            syncStateTracker = tracker,
            scope = backgroundScope,
            debounceMs = 1L,
            logger = { logs += it }
        )
        runCurrent()
        advanceTimeBy(2L)
        advanceUntilIdle()
        repository.pushCalls = 0
        repository.bootstrapCalls = 0

        coordinator.onLocalCatalogChanged()
        advanceTimeBy(2L)
        advanceUntilIdle()

        assertEquals(0, repository.pushCalls)
        assertTrue(logs.any { it.contains("reason=no_pending_catalog_work") })
        assertEquals(false, tracker.isSyncing.value)
        coordinator.shutdown()
    }

    private class FakeCatalogAutoSyncRepository043 : CatalogAutoSyncRepository {
        var pushCalls = 0
        var quickWithEventsCalls = 0
        var drainCalls = 0
        var bootstrapCalls = 0
        var shouldBootstrap = true
        var hasPendingWork = true
        var nextQuickSummary: CatalogSyncSummary = emptySummary(pushedProducts = 1, pushedProductPrices = 1)
        var nextDrainSummary: CatalogSyncSummary = emptySummary(pulledProducts = 1)
        var failQuick: Throwable? = null
        var failQuickOnce: Throwable? = null
        var failDrain: Throwable? = null
        var clearPendingOnPush = false

        override suspend fun shouldRunCatalogBootstrap(ownerUserId: String): Boolean = shouldBootstrap

        override suspend fun hasCatalogCloudPendingWorkInclusive(): Boolean = hasPendingWork

        override suspend fun pushDirtyCatalogDeltaToRemote(
            remote: CatalogRemoteDataSource,
            priceRemote: ProductPriceRemoteDataSource,
            ownerUserId: String,
            progressReporter: CatalogSyncProgressReporter
        ): Result<CatalogSyncSummary> {
            pushCalls++
            progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.PUSH_PRODUCTS))
            if (clearPendingOnPush) hasPendingWork = false
            return Result.success(emptySummary(pushedProducts = 1, pushedProductPrices = 1))
        }

        override suspend fun syncCatalogQuickWithEvents(
            remote: CatalogRemoteDataSource,
            priceRemote: ProductPriceRemoteDataSource,
            syncEventRemote: SyncEventRemoteDataSource,
            ownerUserId: String,
            progressReporter: CatalogSyncProgressReporter,
            sessionRemote: SessionBackupRemoteDataSource?
        ): Result<CatalogSyncSummary> {
            quickWithEventsCalls++
            progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.PUSH_PRODUCTS))
            failQuickOnce?.let {
                failQuickOnce = null
                return Result.failure(it)
            }
            failQuick?.let { return Result.failure(it) }
            if (clearPendingOnPush) hasPendingWork = false
            return Result.success(nextQuickSummary)
        }

        override suspend fun drainSyncEventsFromRemote(
            remote: CatalogRemoteDataSource,
            priceRemote: ProductPriceRemoteDataSource,
            syncEventRemote: SyncEventRemoteDataSource,
            ownerUserId: String,
            progressReporter: CatalogSyncProgressReporter,
            sessionRemote: SessionBackupRemoteDataSource?
        ): Result<CatalogSyncSummary> {
            drainCalls++
            progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.SYNC_EVENTS_DRAIN))
            failDrain?.let { return Result.failure(it) }
            return Result.success(nextDrainSummary)
        }

        override suspend fun pullCatalogBootstrapFromRemote(
            remote: CatalogRemoteDataSource,
            priceRemote: ProductPriceRemoteDataSource,
            progressReporter: CatalogSyncProgressReporter
        ): Result<CatalogSyncSummary> {
            bootstrapCalls++
            progressReporter.onProgress(CatalogSyncProgressState.running(CatalogSyncStage.PULL_CATALOG))
            return Result.success(emptySummary(pulledProducts = 1, pulledProductPrices = 1))
        }
    }

    private class FakeCatalogRemote043(
        private val configured: Boolean = true
    ) : CatalogRemoteDataSource {
        var configurationReads = 0

        override val isConfigured: Boolean
            get() {
                configurationReads += 1
                return configured
            }

        override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>): Result<Unit> = Result.success(Unit)
        override suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit> = Result.success(Unit)
        override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> = Result.success(Unit)
        override suspend fun fetchCatalog(): Result<InventoryCatalogFetchBundle> =
            Result.success(InventoryCatalogFetchBundle(emptyList(), emptyList(), emptyList()))
        override suspend fun fetchCatalogByIds(
            supplierIds: Set<String>,
            categoryIds: Set<String>,
            productIds: Set<String>
        ): Result<InventoryCatalogFetchBundle> =
            Result.success(InventoryCatalogFetchBundle(emptyList(), emptyList(), emptyList()))
        override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch): Result<Unit> = Result.success(Unit)
        override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch): Result<Unit> = Result.success(Unit)
        override suspend fun markProductTombstoned(patch: CatalogTombstonePatch): Result<Unit> = Result.success(Unit)
    }

    private class FakePriceRemote043(
        override val isConfigured: Boolean = true
    ) : ProductPriceRemoteDataSource {
        override suspend fun upsertProductPrices(rows: List<InventoryProductPriceRow>): Result<Unit> = Result.success(Unit)
        override suspend fun fetchProductPrices(): Result<List<InventoryProductPriceRow>> = Result.success(emptyList())
        override suspend fun fetchProductPricesByIds(remoteIds: Set<String>): Result<List<InventoryProductPriceRow>> =
            Result.success(emptyList())
    }

    private class FakeSyncEventRemote043(
        private val configured: Boolean = true
    ) : SyncEventRemoteDataSource {
        var configurationReads = 0

        override val isConfigured: Boolean
            get() {
                configurationReads += 1
                return configured
            }

        override suspend fun checkCapabilities(ownerUserId: String): Result<SyncEventRemoteCapabilities> =
            Result.success(
                SyncEventRemoteCapabilities(
                    syncEventsAvailable = true,
                    recordSyncEventAvailable = true,
                    realtimeSyncEventsAvailable = true
                )
            )

        override suspend fun recordSyncEvent(params: SyncEventRecordRpcParams): Result<SyncEventRemoteRow> =
            Result.failure(UnsupportedOperationException("not used"))

        override suspend fun fetchSyncEventsAfter(
            ownerUserId: String,
            storeId: String?,
            afterId: Long,
            limit: Long
        ): Result<List<SyncEventRemoteRow>> =
            Result.success(emptyList())
    }

    private class FakeShopDeviceRegistrationRemote072(
        private val status: String
    ) : ShopDeviceRegistrationRemote {
        override val isConfigured: Boolean = true
        var nextStatus: Result<ShopDeviceAuthorizationSnapshot>? = null
        var onStatusCall: (() -> Unit)? = null
        var statusCalls = 0

        override suspend fun registerCurrentOwnerDevice(reason: String): Result<ShopDeviceRegistrationResult> =
            Result.success(ShopDeviceRegistrationResult(ok = true, code = "success"))

        override suspend fun currentOwnerDeviceStatus(reason: String): Result<ShopDeviceAuthorizationSnapshot> {
            statusCalls += 1
            onStatusCall?.invoke()
            return nextStatus?.also { nextStatus = null } ?: Result.success(
                ShopDeviceAuthorizationSnapshot(
                    status = status,
                    code = if (status == "active") "success" else status,
                    canWrite = status == "active",
                    serverTime = "2026-06-19T00:00:00Z",
                    lastSeenAt = "2026-06-19T00:00:00Z",
                    reasonCode = status,
                    recommendedAction = if (status == "active") "allow" else "contact_shop_admin",
                    checkedAtMs = System.currentTimeMillis()
                )
            )
        }
    }

    private companion object {
        const val USER_ID = "00000000-0000-4000-8000-000000000043"
        const val SHOP_ID = "00000000-0000-4000-8000-000000000139"

        fun selectedShop(shopId: String): SelectedShop =
            SelectedShop(
                shopId = shopId,
                code = "synthetic-shop",
                name = "Synthetic shop",
                role = "owner",
                status = "active",
                canWrite = true
            )

        fun emptySummary(
            pushedProducts: Int = 0,
            pulledProducts: Int = 0,
            pushedProductPrices: Int = 0,
            pulledProductPrices: Int = 0
        ): CatalogSyncSummary =
            CatalogSyncSummary(
                pushedSuppliers = 0,
                pushedCategories = 0,
                pushedProducts = pushedProducts,
                pulledSuppliers = 0,
                pulledCategories = 0,
                pulledProducts = pulledProducts,
                pushedProductPrices = pushedProductPrices,
                pulledProductPrices = pulledProductPrices,
                deferredProductPricesNoProductRef = 0,
                skippedProductPricesPullNoProductRef = 0,
                priceSyncFailed = false
            )
    }
}
