package com.example.merchandisecontrolsplitview.ui.navigation

import android.graphics.Bitmap
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.NativeAckContract
import com.example.merchandisecontrolsplitview.data.ScopeSwitchFixture
import com.example.merchandisecontrolsplitview.data.nativeDurableWrite
import com.example.merchandisecontrolsplitview.data.nativeSha
import com.example.merchandisecontrolsplitview.data.verifyAutomaticRecoveryLocal
import com.example.merchandisecontrolsplitview.ui.theme.MerchandiseControlTheme
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*

/** UI driver only: actual production NavHost, Options discovery, conflict dialog and editor Save.
 * Durable row/attempt/receipt/price/remote-call oracles belong to NativeResolutionOracle.
 * No ViewModel state, repository decision or business row is fabricated by this driver.
 */
internal class NativeResolutionUiDriver(private val compose: ComposeContentTestRule) {
    private val visible = mutableStateOf<ScopeSwitchFixture?>(null)
    private var mounted: ScopeSwitchFixture? = null

    fun installContent() = compose.setContent {
        visible.value?.let { fixture ->
            val auth by fixture.auth.state.collectAsState()
            val identity by fixture.auth.localIdentity.collectAsState()
            val shops by fixture.shops.state.collectAsState()
            val business by fixture.tracker.businessDataScopeState.collectAsState()
            val progress by fixture.tracker.state.collectAsState()
            val activityResultRegistryOwner = checkNotNull(LocalActivityResultRegistryOwner.current)
            CompositionLocalProvider(LocalContext provides fixture.app,
                LocalViewModelStoreOwner provides fixture.viewModelOwner,
                LocalActivityResultRegistryOwner provides activityResultRegistryOwner) {
                MerchandiseControlTheme {
                    AppNavGraphContent(fixture.app, fixture.excelVM, fixture.databaseVM,
                        true, auth, progress, shops, business, localIdentity = identity,
                        localIdentityCurrent = identity?.let(fixture.auth::isCurrentLocalIdentity) == true)
                }
            }
        }
    }

    suspend fun mount(fixture: ScopeSwitchFixture, allowPendingRecovery: Boolean = false) {
        check(mounted == null)
        if (allowPendingRecovery) fixture.verifyAutomaticRecoveryLocal() else fixture.verifyResolutionReady()
        fixture.mountViewModels()
        mounted = fixture
        compose.runOnIdle { visible.value = fixture }
        compose.waitForIdle()
    }

    fun unmount() {
        compose.runOnIdle { visible.value = null }
        compose.waitForIdle()
        mounted = null
    }

    private fun current(): ScopeSwitchFixture = requireNotNull(mounted)

    private fun openNormalEditor(barcode: String = NativeAckContract.BARCODE): Long {
        compose.onNodeWithTag("root-tab-databaseScreen").performClick().assertIsSelected()
        compose.onNodeWithTag("database-search").performTextReplacement(barcode)
        val row = hasText(barcode, substring = true) and
            hasAnyAncestor(hasTestTag("database-product-list"))
        compose.waitUntil(10_000) { compose.onAllNodes(row).fetchSemanticsNodes().size == 1 }
        compose.onNode(row).assertIsDisplayed().performClick()
        compose.onNodeWithTag("task141.edit.dialog-root").assertIsDisplayed()
        val target = requireNotNull(current().databaseVM.productEditorTarget.value)
        assertEquals(barcode, target.barcode)
        return target.id
    }

    fun saveNameThroughEditor(value: String, barcode: String = NativeAckContract.BARCODE,
        evidencePhase: String = "save-a-draft"): Long {
        val id = openNormalEditor(barcode)
        compose.onNodeWithTag("product-editor-name").performScrollTo().performClick().performTextReplacement(value)
        compose.onNodeWithTag("product-editor-name").assertTextContains(value)
        captureUi(evidencePhase)
        saveAndAwaitEditorDismissal()
        current().record("ui_normal_save_returned", buildJsonObject { put("field", "productname"); put("productId", id) })
        return id
    }

    fun saveNameAndRetailPriceThroughEditor(name: String, retailPriceText: String): Long {
        val id = openNormalEditor()
        compose.onNodeWithTag("product-editor-name").performScrollTo().performClick()
        compose.onNodeWithTag("product-editor-name").performTextReplacement(name)
        compose.onNodeWithTag("product-editor-name").assertTextContains(name)
        compose.onNodeWithTag("product-editor-retail-price").performScrollTo().performClick()
        compose.onNodeWithTag("product-editor-retail-price").performTextReplacement(retailPriceText)
        compose.onNodeWithTag("product-editor-retail-price").assertTextContains(retailPriceText)
        captureUi("save-name-retail-price-draft")
        saveAndAwaitEditorDismissal()
        current().record("ui_normal_save_returned", buildJsonObject {
            put("fields", JsonArray(listOf("productname", "retailprice").map(::JsonPrimitive)))
            put("productId", id)
        })
        return id
    }

    fun clearItemNumberThroughEditor(expectedPreviousValue: String): Long {
        val id = openNormalEditor()
        compose.onNode(hasSetTextAction() and hasText(expectedPreviousValue, substring = false))
            .performScrollTo().performClick().performTextReplacement("")
        // Empty strings can match other optional fields; do not use an ambiguous post-edit matcher.
        captureUi("explicit-null-draft")
        saveAndAwaitEditorDismissal()
        current().record("ui_normal_save_returned", buildJsonObject { put("field", "itemnumber"); put("explicitNull", true); put("productId", id) })
        return id
    }

    fun saveItemNumberThroughEditor(expectedPreviousValue: String, value: String): Long {
        val id = openNormalEditor()
        // Actual persisted field value, not a synthetic setter or an ambiguous product row.
        val field = hasSetTextAction() and hasText(expectedPreviousValue, substring = false)
        compose.onNode(field).performScrollTo().performClick().performTextReplacement(value)
        compose.onNode(hasSetTextAction() and hasText(value, substring = false)).assertIsDisplayed()
        captureUi("save-b-draft")
        saveAndAwaitEditorDismissal()
        current().record("ui_normal_save_returned", buildJsonObject { put("field", "itemnumber"); put("productId", id) })
        return id
    }

    private fun saveAndAwaitEditorDismissal() {
        compose.onNodeWithTag("product-editor-save").assertIsEnabled().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("task141.edit.dialog-root").fetchSemanticsNodes().isEmpty()
        }
        assertNull(current().databaseVM.productEditorTarget.value)
    }

    fun openReviewFromOptions() {
        compose.onNodeWithTag("root-tab-optionsScreen").performClick().assertIsSelected()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("options-persisted-conflict-review").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("options-persisted-conflict-review").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { current().databaseVM.persistedCatalogConflictReview.value?.loading == false }
        compose.onNodeWithTag("catalog-conflict-review").assertIsDisplayed()
        assertNotNull(current().databaseVM.persistedCatalogConflictReview.value?.context)
        current().record("ui_persisted_review_opened_via_options")
    }

    fun adoptRemote() {
        compose.onNodeWithTag("catalog-conflict-adopt-remote").assertIsEnabled().performClick()
    }

    fun adoptTombstone() {
        compose.onNodeWithTag("catalog-conflict-adopt-tombstone").assertIsEnabled().performClick()
    }

    fun awaitTombstoneReady() {
        compose.waitUntil(10_000) {
            current().databaseVM.persistedCatalogConflictReview.value?.let {
                !it.loading && !it.resolving && it.context?.let { context -> context.remoteDeleted && context.canResolve } == true
            } == true
        }
        compose.onNodeWithTag("catalog-conflict-adopt-tombstone").assertIsEnabled()
    }

    fun selectField(field: String) {
        require(field in setOf("barcode", "itemnumber", "productname", "secondproductname",
            "purchaseprice", "retailprice", "supplier", "category", "stockquantity"))
        compose.onNodeWithTag("catalog-conflict-field-$field").performScrollTo().assertIsEnabled().performClick()
            .assertIsOn()
    }

    fun applySelected() {
        compose.onNodeWithTag("catalog-conflict-apply-selected").assertIsEnabled().performClick()
    }

    fun awaitResolved() {
        compose.waitUntil(10_000) { current().databaseVM.persistedCatalogConflictReview.value?.resolved == true }
        compose.onNodeWithTag("catalog-conflict-review").assertIsDisplayed()
        compose.onNodeWithTag("catalog-conflict-adopt-remote").assertDoesNotExist()
        compose.onNodeWithTag("catalog-conflict-apply-selected").assertDoesNotExist()
    }

    fun awaitRefreshRequired() {
        compose.waitUntil(10_000) {
            current().databaseVM.persistedCatalogConflictReview.value?.let { !it.resolving && it.requiresRefresh } == true
        }
        assertFalse(requireNotNull(current().databaseVM.persistedCatalogConflictReview.value).resolved)
        compose.onNodeWithTag("catalog-conflict-apply-selected").assertIsNotEnabled()
        compose.onNodeWithTag("catalog-conflict-message").assertIsDisplayed()
    }

    fun awaitDecisionReady() {
        compose.waitUntil(10_000) {
            current().databaseVM.persistedCatalogConflictReview.value?.let {
                !it.resolving && !it.loading && !it.resolved && !it.requiresRefresh && it.context?.canResolve == true
            } == true
        }
        compose.onNodeWithTag("catalog-conflict-adopt-remote").assertIsEnabled()
    }

    fun refreshComparison() {
        compose.onNodeWithText(current().app.getString(R.string.catalog_conflict_refresh_comparison))
            .assertIsEnabled().performClick()
    }

    fun dismissReview() {
        compose.onNodeWithTag("catalog-conflict-dismiss").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("catalog-conflict-review").fetchSemanticsNodes().isEmpty() }
    }

    fun cancelUnderlyingEditor() {
        if (compose.onAllNodesWithTag("task141.edit.dialog-root").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(hasText(current().app.getString(R.string.cancel)) and
                hasAnyAncestor(hasTestTag("task141.edit.dialog-root"))).performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("task141.edit.dialog-root").fetchSemanticsNodes().isEmpty() }
        }
    }

    fun captureUi(phase: String) {
        require(Regex("[a-z0-9-]+").matches(phase))
        val fixture = current()
        val root = when {
            compose.onAllNodesWithTag("catalog-conflict-review").fetchSemanticsNodes().isNotEmpty() ->
                compose.onNodeWithTag("catalog-conflict-review")
            compose.onAllNodesWithTag("task141.edit.dialog-root").fetchSemanticsNodes().isNotEmpty() ->
                compose.onNodeWithTag("task141.edit.dialog-root")
            else -> compose.onRoot()
        }
        val bytes = ByteArrayOutputStream().use { output ->
            check(root.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        val file = File(fixture.context.root, "ui-$phase.png")
        check(!file.exists()) { "Do not overwrite prior UI evidence" }
        nativeDurableWrite(file, bytes)
        val text = compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().flatMap { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
            }
        fixture.record("ui_witness", buildJsonObject {
            put("phase", phase); put("pngSha256", nativeSha(bytes)); put("visibleTexts", JsonArray(text.map(::JsonPrimitive)))
            put("acceptance_boundary", "actual native UI with synthetic owned transport; storage receipt and remote calls require independent oracle")
        })
    }
}
