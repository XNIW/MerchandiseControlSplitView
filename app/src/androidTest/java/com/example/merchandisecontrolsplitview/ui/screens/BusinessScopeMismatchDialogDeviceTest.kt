package com.example.merchandisecontrolsplitview.ui.screens

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.Task126BusinessDataScopeStatus
import com.example.merchandisecontrolsplitview.ui.theme.MerchandiseControlTheme
import com.example.merchandisecontrolsplitview.viewmodel.LocalDatabaseStatusUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BusinessScopeMismatchDialogDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs by lazy {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    @Before
    fun clearPresentationFixture() {
        prefs.edit()
            .remove(BUSINESS_SCOPE_MISMATCH_AUTO_SHOWN_IDENTITIES_PREF)
            .commit()
    }

    @After
    fun clearPresentationFixtureAfterTest() {
        prefs.edit()
            .remove(BUSINESS_SCOPE_MISMATCH_AUTO_SHOWN_IDENTITIES_PREF)
            .commit()
    }

    @Test
    fun accountMismatchShowsExactlyTwoChoicesKeepIsSafeAndManualReviewReopens() {
        var replaceCalls = 0
        setMismatchContent(
            status = Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH,
            canReplace = true,
            onReplace = { replaceCalls += 1 }
        )

        assertChoiceDialogVisible(replaceEnabled = true)
        composeRule.onNodeWithText(keepLabel()).performClick()
        composeRule.onNodeWithText(title()).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, replaceCalls) }
        composeRule.onNodeWithText(
            context.getString(R.string.local_database_status_products)
        ).assertDoesNotExist()
        composeRule.onNodeWithText(
            context.getString(R.string.local_database_status_history_sessions)
        ).assertDoesNotExist()
        composeRule.onNodeWithText(
            context.getString(R.string.business_scope_pending_outbox)
        ).assertDoesNotExist()

        composeRule.onNodeWithText(context.getString(R.string.business_scope_mismatch_review))
            .performClick()
        assertChoiceDialogVisible(replaceEnabled = true)
        composeRule.onNodeWithText(replaceLabel()).performClick()

        composeRule.runOnIdle { assertEquals(1, replaceCalls) }
        composeRule.onNodeWithText(title()).assertDoesNotExist()
        composeRule.onNodeWithText(
            context.getString(R.string.business_scope_discard_confirm_title)
        ).assertDoesNotExist()
    }

    @Test
    fun shopMismatchShowsTheSameChoiceDialog() {
        setMismatchContent(
            status = Task126BusinessDataScopeStatus.BLOCKED_SHOP_MISMATCH,
            canReplace = true
        )

        assertChoiceDialogVisible(replaceEnabled = true)
    }

    @Test
    fun backDismissesAsKeepLocalWithoutCallingReplace() {
        var replaceCalls = 0
        setMismatchContent(
            status = Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH,
            canReplace = true,
            onReplace = { replaceCalls += 1 }
        )
        composeRule.onNodeWithText(title()).assertIsDisplayed()

        Espresso.pressBack()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(title()).assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, replaceCalls) }
    }

    @Test
    fun unresolvedShopLeavesDestructiveChoiceDisabled() {
        var replaceCalls = 0
        setMismatchContent(
            status = Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH,
            canReplace = false,
            onReplace = { replaceCalls += 1 }
        )

        composeRule.onNodeWithText(title()).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.business_scope_mismatch_review))
            .performClick()
        assertChoiceDialogVisible(replaceEnabled = false)
        composeRule.runOnIdle { assertEquals(0, replaceCalls) }
    }

    private fun setMismatchContent(
        status: Task126BusinessDataScopeStatus,
        canReplace: Boolean,
        onReplace: () -> Unit = {}
    ) {
        composeRule.setContent {
            MerchandiseControlTheme(darkTheme = false) {
                LocalDatabaseStatusSection(
                    state = LocalDatabaseStatusUiState(
                        productsCount = 1,
                        suppliersCount = 2,
                        categoriesCount = 3,
                        priceHistoryCount = 4,
                        historySessionsCount = 5,
                        pendingLocalChangesCount = 6,
                        syncEventOutboxPendingCount = 7,
                        isLoading = false,
                        businessDataScopeStatus = status
                    ),
                    onDiscardUnboundLocalData = {
                        error("unbound discard must not be reachable from mismatch dialog")
                    },
                    onReplaceMismatchedLocalData = onReplace,
                    mismatchIdentity = "a".repeat(64),
                    canReplaceMismatchedLocalData = canReplace
                )
            }
        }
    }

    private fun assertChoiceDialogVisible(replaceEnabled: Boolean) {
        composeRule.onNodeWithText(title()).assertIsDisplayed()
        composeRule.onNodeWithText(message()).assertIsDisplayed()
        composeRule.onNodeWithText(keepLabel()).assertIsDisplayed().assertIsEnabled()
        val replace = composeRule.onNodeWithText(replaceLabel()).assertIsDisplayed()
        if (replaceEnabled) replace.assertIsEnabled() else replace.assertIsNotEnabled()
        composeRule.onNodeWithText(
            context.getString(R.string.business_scope_discard_confirm_action)
        ).assertDoesNotExist()
    }

    private fun title(): String =
        context.getString(R.string.business_scope_mismatch_choice_title)

    private fun message(): String =
        context.getString(R.string.business_scope_mismatch_choice_message)

    private fun keepLabel(): String =
        context.getString(R.string.business_scope_mismatch_keep_local)

    private fun replaceLabel(): String =
        context.getString(R.string.business_scope_replace_with_cloud)
}
