package com.example.merchandisecontrolsplitview.ui.navigation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.*
import com.example.merchandisecontrolsplitview.ui.theme.MerchandiseControlTheme
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real NavHost/editor/Options and Application scope transition; synthetic isolated authorities. */
@RunWith(AndroidJUnit4::class)
class Task144ScopeSwitchDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val visible = mutableStateOf<ScopeSwitchFixture?>(null)

    @Test fun readinessUsesCompleteScopeValuesAndRejectsStaleLocalEpoch() = runBlocking {
        val expected = task126ActiveOwnerStoreScope(NativeAckContract.OWNER, NativeAckContract.shop)
        val sameValues = task126ActiveOwnerStoreScope(NativeAckContract.OWNER, NativeAckContract.shop)
        assertNotSame(expected, sameValues)
        assertFalse("Scope class has no structural equals", expected == sameValues)
        val state = Task126BusinessDataScopeState.ready(sameValues)
        val tracker = CatalogSyncStateTracker(state)
        val stamp = requireNotNull(tracker.captureLocalPresentationStamp(state))
        assertTrue(scopeSwitchReadyFor(expected, tracker, state, stamp))
        fun scope(owner: String = expected.ownerHash, store: String = expected.storeId,
            local: String = expected.localStoreId, protocol: Int = expected.syncProtocolVersion,
            schema: Int = expected.schemaVersion, epoch: Int = expected.storeEpoch) =
            Task126OwnerStoreScope(owner, store, local, protocol, schema, epoch)
        for (foreign in listOf(scope(owner = "foreign-owner"), scope(store = "shop:foreign"),
            scope(local = "local-foreign"), scope(protocol = expected.syncProtocolVersion + 1),
            scope(schema = expected.schemaVersion + 1), scope(epoch = expected.storeEpoch + 1))) {
            assertFalse(scopeSwitchReadyFor(foreign, tracker, state, stamp))
        }
        assertFalse(scopeSwitchReadyFor(expected, tracker, state, null))
        assertFalse(scopeSwitchReadyFor(expected, tracker, state.copy(boundScope = null), stamp))
        assertFalse(scopeSwitchReadyFor(expected, tracker,
            state.copy(status = Task126BusinessDataScopeStatus.CHECKING), stamp))
        tracker.withBusinessDataScopeTransition {
            assertFalse(scopeSwitchReadyFor(expected, tracker, state, stamp))
        }
        assertEquals("Business state remains equal across the transition", state, tracker.businessDataScopeState.value)
        assertFalse(scopeSwitchReadyFor(expected, tracker, state, stamp))
        assertTrue(scopeSwitchReadyFor(expected, tracker, state,
            requireNotNull(tracker.captureLocalPresentationStamp(state))))
    }

    private fun content() = compose.setContent {
        visible.value?.let { fixture ->
            val auth by fixture.auth.state.collectAsState()
            val shops by fixture.shops.state.collectAsState()
            val business by fixture.tracker.businessDataScopeState.collectAsState()
            val progress by fixture.tracker.state.collectAsState()
            CompositionLocalProvider(LocalContext provides fixture.app,
                LocalViewModelStoreOwner provides fixture.viewModelOwner) {
                MerchandiseControlTheme {
                    AppNavGraphContent(fixture.app, fixture.excelVM, fixture.databaseVM,
                        true, auth, progress, shops, business)
                }
            }
        }
    }
    private suspend fun show(fixture: ScopeSwitchFixture) {
        fixture.mountViewModels()
        compose.runOnIdle { visible.value = fixture }
        compose.waitForIdle()
    }
    private fun openEditor(fixture: ScopeSwitchFixture) {
        compose.onNodeWithTag("root-tab-databaseScreen").performClick().assertIsSelected()
        compose.onNodeWithTag("database-search").performTextReplacement("TASK144-NATIVE")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(NativeAckContract.BARCODE, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("database-product-list").performScrollToNode(hasText(NativeAckContract.BARCODE, substring = true))
        compose.onNodeWithText(NativeAckContract.BARCODE, substring = true).performClick()
        compose.onNodeWithTag("task141.edit.dialog-root").assertIsDisplayed()
        compose.onNodeWithTag("product-editor-name").performScrollTo().assertIsDisplayed()
        assertEquals(NativeAckContract.BARCODE, fixture.databaseVM.productEditorTarget.value?.barcode)
    }
    private fun awaitMismatchDialog(fixture: ScopeSwitchFixture) {
        // Root tabs show the blocked placeholder; its real action opens production Options.
        compose.onNodeWithTag("root-business-placeholder").assertIsDisplayed()
        compose.onNodeWithText(fixture.app.getString(R.string.business_scope_review_details)).performClick()
        compose.onNodeWithTag("root-tab-optionsScreen").assertIsSelected()
        val title = fixture.app.getString(R.string.business_scope_mismatch_choice_title)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText(fixture.app.getString(R.string.business_scope_replace_with_cloud)).assertIsEnabled()
    }
    private fun awaitOptionsBody(fixture: ScopeSwitchFixture) {
        val products = fixture.app.getString(R.string.local_database_status_products)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(products).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(products).performScrollTo().assertIsDisplayed()
        val emptyReady = fixture.app.getString(R.string.local_database_status_empty)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(emptyReady).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(emptyReady).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(fixture.app.getString(R.string.business_scope_mismatch_choice_title)).assertCountEquals(0)
        compose.waitForIdle()
    }
    private fun uiText(): List<String> = compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } }.sorted()

    /** Cleanup phases are independent; terminal completion cannot precede successful cleanup. */
    private suspend fun finalizeOwned(fixture: ScopeSwitchFixture?, primary: Throwable?) = withContext(NonCancellable) {
        if (fixture == null) return@withContext
        var failure = primary
        suspend fun attempt(block: suspend () -> Unit) {
            try { block() } catch (secondary: Throwable) {
                val first = failure
                if (first == null) failure = secondary else if (first !== secondary) first.addSuppressed(secondary)
            }
        }
        if (primary != null) attempt { fixture.record("FAIL", buildJsonObject { put("type", primary::class.java.name) }) }
        attempt { compose.runOnIdle { visible.value = null }; compose.waitForIdle() }
        attempt { fixture.close() }
        if (failure == null) attempt { fixture.record("completed", buildJsonObject { put("status", "PASS_AFTER_CLEANUP") }) }
        if (failure != null) attempt { fixture.record("terminal_failure", buildJsonObject {
            put("type", requireNotNull(failure)::class.java.name)
            put("suppressedCount", requireNotNull(failure).suppressed.size)
        }) }
        if (primary == null) failure?.let { throw it }
    }

    @Test fun priorScopeDraftRemainsIsolatedAndRestorableBeforeReplacement() = runBlocking {
        withTimeout(150_000) {
            content()
            var active: ScopeSwitchFixture? = null
            var primary: Throwable? = null
            try {
                val fixture = ScopeSwitchFixture(scopeSwitchContext("draft", true)).also { active = it }
                fixture.seed(); fixture.openRuntime(); fixture.beginObservation()
                fixture.verifyReady(NativeAckContract.SHOP); show(fixture)
                val before = fixture.saveImage("before-draft")
                fixture.savePreferences("before-draft")
                val writes = fixture.businessRecords()
                val committed = fixture.backend.state()
                openEditor(fixture)
                val draft = "Unsaved scope A draft ${fixture.context.runId}"
                compose.onNodeWithTag("product-editor-name").performClick().performTextReplacement(draft)
                compose.onNodeWithTag("product-editor-name").assertTextContains(draft)
                fixture.record("draft_captured_from_actual_editor", buildJsonObject { put("text", draft) })
                assertEquals(before, fixture.saveImage("after-edit-before-switch"))
                assertTrue(fixture.shops.selectShop(ScopeSwitchContract.SHOP_B))
                fixture.awaitScope(ScopeSwitchContract.SHOP_B, Task126BusinessDataScopeStatus.BLOCKED_SHOP_MISMATCH)
                awaitMismatchDialog(fixture)
                compose.onAllNodesWithTag("task141.edit.dialog-root").assertCountEquals(0)
                compose.onAllNodesWithTag("product-editor-name").assertCountEquals(0)
                assertEquals(before, fixture.saveImage("b-unconfirmed-preserves-a"))
                fixture.savePreferences("b-unconfirmed-preserves-a")
                assertEquals(writes, fixture.businessRecords())
                assertEquals(committed, fixture.backend.state())
                assertNull(fixture.db.syncRecoveryJournalDao().get())
                // Only keep-local; never invoke the destructive replacement action in this case.
                compose.onNodeWithText(fixture.app.getString(R.string.business_scope_mismatch_keep_local)).performClick()
                assertTrue(fixture.shops.selectShop(NativeAckContract.SHOP))
                fixture.verifyReady(NativeAckContract.SHOP)
                openEditor(fixture)
                assertEquals(before, fixture.saveImage("a-returned-before-draft-oracle"))
                assertEquals(writes, fixture.businessRecords())
                assertEquals(committed, fixture.backend.state())
                fixture.record("draft_oracle_ready", buildJsonObject { put("expectedText", draft) })
                // Deliberately free to fail: production has no candidate draft carrier or bypass.
                compose.onNodeWithTag("product-editor-name").assertTextContains(draft)
            } catch (error: Throwable) { primary = error; throw error }
            finally { finalizeOwned(active, primary) }
        }
    }

    @Test fun qualifiedScopeBDiscardsOlderLinkedShopsResponseAfterUiConfirmedReplacement() = runBlocking {
        withTimeout(150_000) {
            content()
            var active: ScopeSwitchFixture? = null
            var primary: Throwable? = null
            try {
                val fixture = ScopeSwitchFixture(scopeSwitchContext("late-response", true)).also { active = it }
                fixture.seed(); fixture.openRuntime(); fixture.beginObservation()
                fixture.verifyReady(NativeAckContract.SHOP); show(fixture)
                val before = fixture.saveImage("before-old-refresh")
                val generationA = requireNotNull(fixture.db.syncRecoveryBaselineDao().get()).generationId
                val committedA = fixture.backend.state()
                val writes = fixture.businessRecords()
                fixture.startOldRefresh()
                fixture.publishNewAuthority() // Completes while old real RPC is held outside ShopContext's lock.
                assertTrue(fixture.shops.selectShop(ScopeSwitchContract.SHOP_B))
                fixture.awaitScope(ScopeSwitchContract.SHOP_B, Task126BusinessDataScopeStatus.BLOCKED_SHOP_MISMATCH)
                awaitMismatchDialog(fixture)
                assertEquals(before, fixture.saveImage("before-ui-confirmation"))
                assertEquals(writes, fixture.businessRecords())
                assertNull(fixture.db.syncRecoveryJournalDao().get())
                fixture.record("actual_options_replacement_confirmation")
                compose.onNodeWithText(fixture.app.getString(R.string.business_scope_replace_with_cloud)).performClick()
                // Actual Application schedules actual coordinator and publishes readiness from its receipt.
                fixture.verifyReady(ScopeSwitchContract.SHOP_B)
                assertNotEquals(generationA, requireNotNull(fixture.db.syncRecoveryBaselineDao().get()).generationId)
                assertEquals(0, fixture.db.productDao().count())
                awaitOptionsBody(fixture)
                val bodyB = fixture.saveImage("qualified-b-before-late-a")
                val scopeB = fixture.tracker.businessDataScopeState.value
                val authorityB = fixture.shops.state.value
                val cachedB = fixture.selected.getConfirmedContext(NativeAckContract.OWNER)
                val preferencesB = fixture.savePreferences("qualified-b-before-late-a")
                val outcomeB = fixture.tracker.lastQualifiedOutcome.value
                val uiB = uiText()
                assertEquals(writes, fixture.businessRecords())
                fixture.record("late_callback_oracle_ready")
                fixture.finishOldRefresh()
                compose.waitForIdle()
                assertEquals(authorityB, fixture.shops.state.value)
                assertEquals(cachedB, fixture.selected.getConfirmedContext(NativeAckContract.OWNER))
                assertEquals(preferencesB, fixture.savePreferences("qualified-b-after-late-a"))
                assertEquals(scopeB, fixture.tracker.businessDataScopeState.value)
                assertEquals(outcomeB, fixture.tracker.lastQualifiedOutcome.value)
                assertEquals(bodyB, fixture.saveImage("qualified-b-after-late-a"))
                assertEquals(committedA, fixture.backend.state())
                assertEquals(writes, fixture.businessRecords())
                assertEquals(uiB, uiText())
                fixture.verifyReady(ScopeSwitchContract.SHOP_B)
            } catch (error: Throwable) { primary = error; throw error }
            finally { finalizeOwned(active, primary) }
        }
    }
}
