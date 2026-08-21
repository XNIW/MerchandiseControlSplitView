package com.example.merchandisecontrolsplitview.ui.screens

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.StorefrontCategory
import com.example.merchandisecontrolsplitview.data.StorefrontEditorDraft
import com.example.merchandisecontrolsplitview.data.StorefrontPublication
import com.example.merchandisecontrolsplitview.ui.theme.MerchandiseControlTheme
import com.example.merchandisecontrolsplitview.viewmodel.ProductImageUiState
import com.example.merchandisecontrolsplitview.viewmodel.ProductImageUiStatus
import com.example.merchandisecontrolsplitview.viewmodel.StorefrontEditorUiState
import com.example.merchandisecontrolsplitview.viewmodel.StorefrontListFilter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorefrontEditorComposeDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun collapsedSummaryExpandsToSeparateOperationalAndPublicationActions() {
        val state = mutableStateOf(
            StorefrontEditorUiState(
                enabled = true,
                remoteProductId = REMOTE_ID,
                publication = publication(),
                draft = StorefrontEditorDraft.fromPublication(publication()),
                categories = listOf(
                    StorefrontCategory(
                        categoryId = CATEGORY_ID,
                        publicName = "Beverages",
                        status = "published",
                        updatedAt = "2026-08-21T12:00:00Z"
                    )
                )
            )
        )
        composeRule.setContent {
            MerchandiseControlTheme(darkTheme = false) {
                Surface {
                    StorefrontEditorSection(
                        product = product(),
                        state = state.value,
                        operationalImageState = ProductImageUiState(ProductImageUiStatus.ABSENT),
                        onExpandedChange = { state.value = state.value.copy(expanded = it) },
                        onDraftChange = { update ->
                            state.value = state.value.copy(draft = update(state.value.draft))
                        },
                        onAlign = {},
                        onAction = {},
                        onPreview = {},
                        onReload = {},
                        onRetryPending = {},
                        onReapplyConflict = {},
                        onCancelConflict = {},
                        onUseOperationalImage = {},
                        onDismissPreview = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("storefront-editor-section").assertHasClickAction()
        composeRule.onNodeWithText(context.getString(R.string.storefront_customer_app)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.storefront_save_draft)).assertDoesNotExist()

        composeRule.onNodeWithTag("storefront-editor-section").performClick()

        composeRule.onNodeWithText(context.getString(R.string.storefront_public_name)).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.storefront_save_draft)).assertIsEnabled()
        composeRule.onNodeWithText(context.getString(R.string.storefront_publish)).assertIsEnabled()
        composeRule.onNodeWithText(
            context.getString(R.string.storefront_operational_save_separate)
        ).assertExists()
        composeRule.onNodeWithText(context.getString(R.string.storefront_pickup))
            .assertHasClickAction()
    }

    @Test
    fun publicCardNeverPresentsOperationalImageAsThePublicVariant() {
        composeRule.setContent {
            MerchandiseControlTheme(darkTheme = false) {
                StorefrontEditorSection(
                    product = product(),
                    state = StorefrontEditorUiState(
                        enabled = true,
                        remoteProductId = REMOTE_ID,
                        publication = publication(),
                        draft = StorefrontEditorDraft.fromPublication(publication())
                    ),
                    operationalImageState = ProductImageUiState(
                        status = ProductImageUiStatus.READY,
                        bytes = byteArrayOf(1, 2, 3)
                    ),
                    onExpandedChange = {},
                    onDraftChange = {},
                    onAlign = {},
                    onAction = {},
                    onPreview = {},
                    onReload = {},
                    onRetryPending = {},
                    onReapplyConflict = {},
                    onCancelConflict = {},
                    onUseOperationalImage = {},
                    onDismissPreview = {}
                )
            }
        }

        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.product_image_state_empty)
            )
        ).assertExists()
    }

    @Test
    fun zeroResultFilterRowKeepsAllAvailable() {
        val selected = mutableStateOf(StorefrontListFilter.DRAFT)
        composeRule.setContent {
            MerchandiseControlTheme(darkTheme = false) {
                StorefrontFilterRow(
                    selected = selected.value,
                    onSelected = { selected.value = it }
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.storefront_filter_all))
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assert(selected.value == StorefrontListFilter.ALL) }
    }

    private fun product() = Product(
        id = 42,
        barcode = "780000000001",
        productName = "Internal tea",
        retailPrice = 1_500.0
    )

    private fun publication() = StorefrontPublication(
        publicationId = PUBLICATION_ID,
        sourceProductId = REMOTE_ID,
        status = "draft",
        publicName = "Public tea",
        storefrontCategoryId = CATEGORY_ID,
        publicPrice = 1_990,
        pickupEnabled = true,
        version = 3,
        updatedAt = "2026-08-21T12:00:00Z",
        mutationSource = "ios"
    )

    private companion object {
        const val REMOTE_ID = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        const val PUBLICATION_ID = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
        const val CATEGORY_ID = "ffffffff-ffff-ffff-ffff-ffffffffffff"
    }
}
