package com.example.merchandisecontrolsplitview.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionsScreenPublicUxTest {
    @Test
    fun `options status cards hide implementation-only pending and account rows`() {
        val source = optionsScreenSource().readText()

        assertFalse(source.contains("local_database_status_pending_changes"))
        assertFalse(source.contains("local_database_status_cloud_account"))
    }

    @Test
    fun `account and automatic sync are rendered in one options card`() {
        val source = optionsScreenSource().readText()

        assertTrue(source.contains("AccountCloudSyncSection("))
        assertTrue(source.contains("showHeader = authState !is AuthState.SignedIn"))
        assertFalse(source.contains("CatalogCloudSection(state = sync)"))
        assertFalse(source.contains("ConnectedAccountCard("))
    }

    @Test
    fun `automatic sync detail requires online-ready UI and business scope`() {
        val source = optionsScreenSource().readText()
        val catalogContent = source
            .substringAfter("private fun CatalogCloudContent(")
            .substringBefore("private fun CatalogCloudBadgeRow(")
        val detailGateStart = catalogContent.indexOf("state.showAutomaticSyncDetail")
        val scopeGateStart = catalogContent.indexOf(
            "state.businessDataScopeStatus == Task126BusinessDataScopeStatus.READY"
        )
        val automaticStatusCopy = catalogContent.indexOf("R.string.catalog_cloud_auto_status_title")
        val allowedGate = catalogContent.substring(
            startIndex = detailGateStart,
            endIndex = catalogContent.indexOf("CatalogCloudDetailBlock(", detailGateStart)
        )

        assertTrue(detailGateStart >= 0)
        assertTrue(scopeGateStart > detailGateStart)
        assertTrue(automaticStatusCopy > scopeGateStart)
        assertTrue(allowedGate.contains("Task126BusinessDataScopeStatus.UNMANAGED_ALLOWED"))
        assertFalse(allowedGate.contains("Task126BusinessDataScopeStatus.REVIEW_REQUIRED_UNBOUND"))
        assertFalse(allowedGate.contains("Task126BusinessDataScopeStatus.BLOCKED_ACCOUNT_MISMATCH"))
        assertFalse(allowedGate.contains("Task126BusinessDataScopeStatus.BLOCKED_SHOP_MISMATCH"))
    }

    @Test
    fun `account and shop mismatch use one native two action choice dialog`() {
        val source = optionsScreenSource().readText()
        val dialog = mismatchDialogSource().readText()
        val navigation = navigationSource().readText()
        val localStatus = source
            .substringAfter("internal fun LocalDatabaseStatusSection(")
            .substringBefore("private fun LocalDatabaseStatusRow(")

        assertTrue(localStatus.contains("BLOCKED_ACCOUNT_MISMATCH"))
        assertTrue(localStatus.contains("BLOCKED_SHOP_MISMATCH"))
        assertTrue(localStatus.contains("BusinessScopeMismatchChoiceDialog("))
        assertTrue(localStatus.contains("business_scope_mismatch_review"))
        assertTrue(localStatus.contains("onReplaceMismatchedLocalData()"))
        assertFalse(localStatus.contains("showReplaceConfirmation"))
        assertFalse(localStatus.contains("business_scope_replace_confirm"))
        assertFalse(localStatus.contains("business_scope_continue_replace"))
        assertFalse(localStatus.contains("business_scope_mismatch_options_message"))

        assertTrue(dialog.contains("androidx.compose.material3.AlertDialog"))
        assertTrue(dialog.contains("business_scope_mismatch_choice_title"))
        assertTrue(dialog.contains("business_scope_mismatch_choice_message"))
        assertTrue(dialog.contains("business_scope_mismatch_keep_local"))
        assertTrue(dialog.contains("business_scope_replace_with_cloud"))
        assertTrue(dialog.contains("onDismissRequest = onKeepLocal"))
        assertTrue(dialog.contains("enabled = canReplace"))
        assertTrue(dialog.contains("MaterialTheme.colorScheme.error"))
        assertEquals(1, Regex("confirmButton\\s*=").findAll(dialog).count())
        assertEquals(1, Regex("dismissButton\\s*=").findAll(dialog).count())
        assertEquals(2, Regex("TextButton\\(").findAll(dialog).count())
        assertTrue(navigation.contains("onReplaceMismatchedLocalData ="))
        assertTrue(navigation.contains("app.replaceMismatchedLocalBusinessDataAndBind()"))
        assertTrue(navigation.contains("signedIn.userId.isNotBlank()"))
        assertTrue(navigation.contains("shopContext.selectedShop?.shopId?.isNotBlank() == true"))
        assertTrue(navigation.contains("businessScopeMismatchIdentity ="))
    }

    @Test
    fun `destructive choice records owner safe intent then recovery activates automatic bootstrap`() {
        val application = applicationSource().readText()
        val replaceBody = application
            .substringAfter("fun replaceMismatchedLocalBusinessDataAndBind()")
            .substringBefore("private fun registerNetworkAutoSyncTrigger()")
        val recoveryCompletion = application
            .substringAfter("private suspend fun completeBusinessRecovery(")
            .substringBefore("private fun cancelBusinessRecovery()")
        val remoteActivationBody = application
            .substringAfter("private suspend fun activateRemoteComponentsForBoundScope(")
            .substringBefore("private fun suspendRemoteComponentsForBusinessScope()")

        assertTrue(replaceBody.contains("repository.replaceMismatchedBusinessDataAndBind(activeScope)"))
        assertTrue(replaceBody.contains("state.errorCode == \"sync_recovery_required\""))
        assertTrue(replaceBody.contains("schedulePendingBusinessRecovery"))
        assertTrue(replaceBody.contains("\"mismatch_replace_confirmed\""))
        assertFalse(replaceBody.contains("state.status == Task126BusinessDataScopeStatus.READY"))
        assertTrue(recoveryCompletion.contains("repository.resolveBusinessDataScope(activeScope)"))
        assertTrue(recoveryCompletion.contains("activateRemoteComponentsForBoundScope"))
        assertTrue(remoteActivationBody.contains("catalogAutoSyncCoordinator.onShopContextChanged()"))
        assertTrue(remoteActivationBody.contains("historySessionPushCoordinator.onShopContextChanged()"))
    }

    @Test
    fun `account email is masked before rendering in options`() {
        assertEquals("x***@example.com", maskEmailForOptions("xuser@example.com"))
        assertEquals("m***@example.test", maskEmailForOptions(" min@example.test "))
        assertNull(maskEmailForOptions("not-an-email"))
    }

    @Test
    fun `history display title treats uuid-only displayName as blank`() {
        val title = formatHistorySessionDisplayTitle(
            displayName = "038aed8a-299c-489a-9c21-d1d70828a4ab",
            supplier = "",
            timestamp = "2026-05-12 20:12:10",
            contextFallback = "Supplier fallback",
            genericFallback = "History fallback"
        )

        assertEquals("History fallback", title)
    }

    private fun optionsScreenSource(): File {
        val candidates = listOf(
            File("app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/OptionsScreen.kt"),
            File("src/main/java/com/example/merchandisecontrolsplitview/ui/screens/OptionsScreen.kt")
        )
        return candidates.first { it.exists() }
    }

    private fun navigationSource(): File {
        val candidates = listOf(
            File("app/src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt"),
            File("src/main/java/com/example/merchandisecontrolsplitview/ui/navigation/NavGraph.kt")
        )
        return candidates.first { it.exists() }
    }

    private fun mismatchDialogSource(): File {
        val candidates = listOf(
            File("app/src/main/java/com/example/merchandisecontrolsplitview/ui/screens/BusinessScopeMismatchDialog.kt"),
            File("src/main/java/com/example/merchandisecontrolsplitview/ui/screens/BusinessScopeMismatchDialog.kt")
        )
        return candidates.first { it.exists() }
    }

    private fun applicationSource(): File {
        val candidates = listOf(
            File("app/src/main/java/com/example/merchandisecontrolsplitview/MerchandiseControlApplication.kt"),
            File("src/main/java/com/example/merchandisecontrolsplitview/MerchandiseControlApplication.kt")
        )
        return candidates.first { it.exists() }
    }
}
