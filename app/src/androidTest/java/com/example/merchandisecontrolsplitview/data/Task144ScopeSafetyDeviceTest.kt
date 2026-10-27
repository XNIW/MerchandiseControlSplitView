package com.example.merchandisecontrolsplitview.data

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.ui.screens.OptionsScreen
import com.example.merchandisecontrolsplitview.ui.theme.MerchandiseControlTheme
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Options/VM consumer, authority repositories and owned disk Room. No full startup claim. */
@RunWith(AndroidJUnit4::class)
class Task144ScopeSafetyDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val visible = mutableStateOf<ScopeSafetyFixture?>(null)

    private fun content() {
        compose.setContent {
            visible.value?.let { fixture ->
                val catalog by fixture.vm.uiState.collectAsState()
                val local by fixture.vm.localDatabaseStatusUi.collectAsState()
                val auth by fixture.auth.state.collectAsState()
                CompositionLocalProvider(LocalContext provides fixture.app) {
                    MerchandiseControlTheme {
                        OptionsScreen(authState = auth, authEnabled = true,
                            catalogSyncUi = catalog, localDatabaseStatusUi = local)
                    }
                }
            }
        }
    }
    private suspend fun show(fixture: ScopeSafetyFixture) {
        fixture.mountViewModel()
        compose.runOnIdle { visible.value = fixture }
        compose.waitUntil(5_000) { !fixture.vm.localDatabaseStatusUi.value.isLoading }
    }
    private fun hide() = compose.runOnIdle { visible.value = null }

    private suspend fun finalizeOwned(fixture: ScopeSafetyFixture?, primary: Throwable?) = withContext(NonCancellable) {
        if (fixture == null) return@withContext // Nominal path already hid and closed it before completed.
        var failure = primary
        suspend fun attempt(block: suspend () -> Unit) {
            try { block() } catch (secondary: Throwable) {
                val first = failure
                if (first == null) failure = secondary else if (first !== secondary) first.addSuppressed(secondary)
            }
        }
        if (primary != null) attempt { fixture?.record("FAIL", buildJsonObject { put("type", primary::class.java.name) }) }
        attempt { hide() }
        attempt { fixture?.close() }
        // The caller rethrows its original failure. Cleanup failure cannot replace it.
        if (primary == null) failure?.let { throw it }
    }

    private fun assertQualifiedOptions(fixture: ScopeSafetyFixture) {
        compose.waitUntil(5_000) { fixture.vm.localDatabaseStatusUi.value.productsCount == 1 }
        assertTrue(fixture.vm.localDatabaseStatusUi.value.localAccessAvailable)
        assertTrue(fixture.vm.localDatabaseStatusUi.value.showBusinessDataDetails)
        compose.onNodeWithText(fixture.app.getString(R.string.local_database_status_products)).performScrollTo().assertIsDisplayed()
        fixture.record("qualified_options_visible")
    }
    private fun assertDiagnosticOptions(fixture: ScopeSafetyFixture) {
        compose.waitUntil(5_000) {
            val local = fixture.vm.localDatabaseStatusUi.value
            !local.isLoading && !local.localAccessAvailable && !local.showBusinessDataDetails
        }
        val local = fixture.vm.localDatabaseStatusUi.value
        assertNull(local.productsCount)
        assertNull(local.suppliersCount)
        assertNull(local.categoriesCount)
        compose.onNodeWithText(fixture.app.getString(R.string.options)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(fixture.app.getString(R.string.local_database_status_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("options-account-email").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(fixture.app.getString(R.string.local_database_status_products)).assertCountEquals(0)
        compose.onAllNodesWithText(fixture.app.getString(R.string.local_database_status_suppliers)).assertCountEquals(0)
        compose.onAllNodesWithText(fixture.app.getString(R.string.local_database_status_categories)).assertCountEquals(0)
        fixture.record("diagnostic_options_visible_business_details_absent", buildJsonObject {
            put("status", local.businessDataScopeStatus.name); put("localAccess", local.localAccessAvailable)
        })
    }
    private suspend fun assertCloudAdmissionDenied(fixture: ScopeSafetyFixture) {
        val entries = fixture.backend.state().calls
        val rawEntries = fixture.businessRecords()
        val failure = runCatching {
            fixture.tracker.withBusinessDataScopeFlight(NativeAckContract.OWNER, NativeAckContract.shop) {
                fixture.repository.pushDirtyCatalogDeltaToRemote(fixture.business, SupabaseProductPriceRemoteDataSource(null),
                    NativeAckContract.OWNER, CatalogSyncProgressReporter { }, NativeAckContract.shop).getOrThrow()
            }
        }.exceptionOrNull()
        assertTrue("Actual flight admission must deny: $failure", failure is Task126BusinessDataScopeChangedException)
        assertEquals(entries, fixture.backend.state().calls)
        assertEquals(rawEntries, fixture.businessRecords())
        fixture.record("cloud_admission_denied")
    }

    @Test fun confirmedMembershipRevocationPreservesRowsAndDeniesLocalAndCloudWork() = runBlocking {
        withTimeout(90_000) {
            content()
            var active: ScopeSafetyFixture? = null
            var primary: Throwable? = null
            try {
                val first = ScopeSafetyFixture(scopeSafetyContext("membership", fresh = true)).also { active = it }
                first.seed(); first.openRuntime(restoreOnly = false); show(first); assertQualifiedOptions(first)
                val before = first.saveImage("before-membership-rejection")
                val calls = first.backend.state().calls
                val businessEntries = first.businessRecords()
                first.membershipResponse = "rejected"
                val rpcBefore = first.rpcRecords().size
                first.shops.refresh(NativeAckContract.OWNER)
                assertEquals(rpcBefore + 1, first.rpcRecords().size)
                assertFalse(first.shops.state.value.localAccessAllowed)
                assertFalse(first.shops.state.value.syncAllowed)
                assertEquals("shop_access_rejected", first.shops.state.value.errorMessage)
                assertNull(first.selected.getConfirmedContext(NativeAckContract.OWNER))
                first.beginOfflineObservation(); first.awaitDenied()
                assertDiagnosticOptions(first)
                first.assertSaveDenied(); assertCloudAdmissionDenied(first)
                assertEquals(calls, first.backend.state().calls)
                assertEquals(businessEntries, first.businessRecords())
                assertEquals(before, first.saveImage("after-membership-rejection"))
                first.savePreferences("denied")
                hide(); first.close(); active = null

                val reopened = ScopeSafetyFixture(scopeSafetyContext("membership", fresh = false)).also { active = it }
                reopened.openRuntime(restoreOnly = true)
                assertNull(reopened.selected.getConfirmedContext(NativeAckContract.OWNER))
                reopened.membershipResponse = "offline"
                reopened.shops.refresh(NativeAckContract.OWNER)
                assertFalse(reopened.shops.state.value.localAccessAllowed)
                reopened.beginOfflineObservation(); reopened.awaitDenied(); show(reopened)
                assertDiagnosticOptions(reopened)
                reopened.assertSaveDenied(); assertCloudAdmissionDenied(reopened)
                assertEquals(calls, reopened.backend.state().calls)
                assertEquals(businessEntries, reopened.businessRecords())
                assertEquals(before, reopened.saveImage("after-reopen-membership-offline"))
                reopened.record("ASSERTIONS_PASSED")
                hide(); reopened.close(); active = null
                reopened.record("completed")
            } catch (error: Throwable) { primary = error; throw error }
            finally { finalizeOwned(active, primary) }
        }
    }

    @Test fun freshRevokedDeviceResponsePersistsDenialAndStopsWriters() = runBlocking {
        withTimeout(90_000) {
            content()
            var active: ScopeSafetyFixture? = null
            var primary: Throwable? = null
            try {
                val first = ScopeSafetyFixture(scopeSafetyContext("device", fresh = true)).also { active = it }
                first.seed(); first.openRuntime(restoreOnly = false); show(first); assertQualifiedOptions(first)
                val before = first.saveImage("before-device-revocation")
                val calls = first.backend.state().calls
                val businessEntries = first.businessRecords()
                first.deviceResponse = "revoked"
                val rpcBefore = first.rpcRecords().size
                // Production Application lazy property includes the real durable denial callback.
                val snapshot = first.app.shopDeviceAuthorizationRepository.checkStatus("task144_native_revocation", force = true, shopId = NativeAckContract.SHOP).getOrThrow()
                assertEquals(rpcBefore + 1, first.rpcRecords().size)
                assertEquals("revoked", snapshot.status)
                assertEquals(NativeAckContract.DEVICE, snapshot.deviceIdentifier)
                assertFalse(snapshot.canWrite)
                assertTrue(first.selected.isDeviceDenied(NativeAckContract.OWNER, NativeAckContract.SHOP, NativeAckContract.DEVICE))
                assertFalse(first.shops.state.value.localAccessAllowed)
                first.awaitDenied(); assertDiagnosticOptions(first); first.assertSaveDenied()
                // Cloud READY alone is insufficient. Exercise the actual guarded transport entry.
                val guarded = DeviceGuardedCatalogRemoteDataSource(first.business, first.app.shopDeviceAuthorizationRepository)
                val failure = guarded.upsertProducts(emptyList(), NativeAckContract.SHOP).exceptionOrNull()
                assertTrue(failure.toString(), failure is ShopDeviceAuthorizationBlockedException)
                assertEquals("revoked", (failure as ShopDeviceAuthorizationBlockedException).snapshot.status)
                assertEquals(calls, first.backend.state().calls)
                assertEquals(businessEntries, first.businessRecords())
                assertEquals(before, first.saveImage("after-device-revocation"))
                first.savePreferences("device-denied")
                hide(); first.close(); active = null

                val reopened = ScopeSafetyFixture(scopeSafetyContext("device", fresh = false)).also { active = it }
                reopened.openRuntime(restoreOnly = true)
                assertTrue(reopened.selected.isDeviceDenied(NativeAckContract.OWNER, NativeAckContract.SHOP, NativeAckContract.DEVICE))
                assertFalse(reopened.shops.state.value.localAccessAllowed)
                // A genuine successful membership RPC must not undo device-specific revocation.
                reopened.shops.refresh(NativeAckContract.OWNER)
                assertTrue(reopened.shops.state.value.syncAllowed)
                assertFalse(reopened.shops.state.value.localAccessAllowed)
                reopened.membershipResponse = "offline"
                reopened.shops.refresh(NativeAckContract.OWNER)
                assertFalse(reopened.shops.state.value.syncAllowed)
                assertFalse(reopened.shops.state.value.localAccessAllowed)
                reopened.beginOfflineObservation(); reopened.awaitDenied(); show(reopened)
                assertDiagnosticOptions(reopened); reopened.assertSaveDenied(); assertCloudAdmissionDenied(reopened)
                assertEquals(calls, reopened.backend.state().calls)
                assertEquals(businessEntries, reopened.businessRecords())
                assertEquals(before, reopened.saveImage("after-reopen-device-denial"))
                reopened.record("ASSERTIONS_PASSED")
                hide(); reopened.close(); active = null
                reopened.record("completed")
            } catch (error: Throwable) { primary = error; throw error }
            finally { finalizeOwned(active, primary) }
        }
    }

    @Test fun corruptNonemptyPhysicalBodyRejectsQualificationAndKeepsDiagnosticOptions() = runBlocking {
        withTimeout(90_000) {
            content()
            var active: ScopeSafetyFixture? = null
            var primary: Throwable? = null
            try {
                val first = ScopeSafetyFixture(scopeSafetyContext("corrupt", fresh = true)).also { active = it }
                first.seed(); first.openRuntime(restoreOnly = false); show(first); assertQualifiedOptions(first)
                val original = first.product()
                val before = first.saveImage("before-controlled-corruption")
                val calls = first.backend.state().calls
                val businessEntries = first.businessRecords()
                // Only the owned synthetic physical row changes; no ref/receipt/proof is rewritten.
                first.db.productDao().update(original.copy(productName = "UNRECORDED CORRUPTION"))
                assertEquals(original.copy(productName = "UNRECORDED CORRUPTION"), first.product())
                val corrupted = first.saveImage("after-controlled-corruption")
                assertNotEquals(before, corrupted)
                val changedTables = before.keys.filter { before[it] != corrupted[it] }
                assertEquals(listOf("products"), changedTables)
                hide(); first.close(); active = null

                val reopened = ScopeSafetyFixture(scopeSafetyContext("corrupt", fresh = false)).also { active = it }
                assertEquals(corrupted, reopened.image())
                reopened.openRuntime(restoreOnly = true)
                assertTrue("Membership cache must remain valid: failure is physical proof", reopened.shops.state.value.localAccessAllowed)
                val resolved = reopened.repository.resolveOfflineBusinessDataScope(NativeAckContract.scope, canWrite = true)
                assertFalse(resolved.allowsLocalOperations)
                assertNull(resolved.localAccessScope)
                assertEquals("local_snapshot_unavailable", resolved.errorCode)
                reopened.beginOfflineObservation(); reopened.awaitDenied(); show(reopened)
                assertDiagnosticOptions(reopened); reopened.assertSaveDenied(); assertCloudAdmissionDenied(reopened)
                assertEquals(calls, reopened.backend.state().calls)
                assertEquals(businessEntries, reopened.businessRecords())
                assertNull(reopened.db.syncRecoveryJournalDao().get())
                assertEquals(corrupted, reopened.saveImage("after-reopen-corruption-denied"))
                reopened.record("ASSERTIONS_PASSED")
                hide(); reopened.close(); active = null
                reopened.record("completed")
            } catch (error: Throwable) { primary = error; throw error }
            finally { finalizeOwned(active, primary) }
        }
    }
}
