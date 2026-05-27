package com.example.merchandisecontrolsplitview.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Task126AccountSwitchReviewUiTest {
    @Test
    fun `dirty account switch exposes recovery choices and blocks cross account push`() {
        val state = Task126ReviewInteractionFixtures.accountSwitchDirty()

        assertEquals(Task126ReviewSurface.AccountSwitchRecovery, state.surface)
        assertEquals(
            listOf(
                Task126UserChoice.Cancel,
                Task126UserChoice.KeepCurrentAccount,
                Task126UserChoice.ExportBackup,
                Task126UserChoice.DiscardPendingAndSwitch
            ),
            state.visibleChoices
        )
        assertTrue(state.isDialogVisible)
        assertFalse(state.isApplying)
        assertEquals(3, state.pendingBefore)

        val cancel = Task126ReviewInteractionReducer.apply(Task126UserChoice.Cancel, state)
        assertEquals("account=A;pending=3", cancel.observedLocalResult)
        assertEquals("blockedCrossAccountPush=true", cancel.observedSyncResult)
        assertEquals(3, cancel.pendingAfter)

        val keepCurrent = Task126ReviewInteractionReducer.apply(Task126UserChoice.KeepCurrentAccount, state)
        assertEquals("account=A;pending=3", keepCurrent.observedLocalResult)
        assertEquals("blockedCrossAccountPush=true", keepCurrent.observedSyncResult)
        assertEquals(3, keepCurrent.pendingAfter)

        val discard = Task126ReviewInteractionReducer.apply(Task126UserChoice.DiscardPendingAndSwitch, state)
        assertEquals("account=B;pending=0", discard.observedLocalResult)
        assertEquals("blockedCrossAccountPush=true;discardConfirmed=true", discard.observedSyncResult)
        assertEquals(0, discard.pendingAfter)
        assertTrue(discard.timeToFinalStateMs >= discard.timeToApplyChoiceMs)
    }

    @Test
    fun `clean account switch to populated account uses light confirmation`() {
        val state = Task126ReviewInteractionFixtures.accountSwitchCleanRemotePopulated()

        assertEquals(0, state.pendingBefore)
        assertEquals(listOf(Task126UserChoice.Cancel, Task126UserChoice.SwitchAccount), state.visibleChoices)

        val cancel = Task126ReviewInteractionReducer.apply(Task126UserChoice.Cancel, state)
        assertEquals("account=A;pending=0", cancel.observedLocalResult)
        assertEquals("cancelled=true;reseed=not-started", cancel.observedSyncResult)

        val outcome = Task126ReviewInteractionReducer.apply(Task126UserChoice.SwitchAccount, state)

        assertEquals("account=B;cache=verified", outcome.observedLocalResult)
        assertEquals("pending=0;reseed=remote-populated", outcome.observedSyncResult)
        assertEquals(0, outcome.pendingAfter)
        assertEquals(0, outcome.conflictCountAfter)
    }

    @Test
    fun `export backup keeps account and pending until discard is confirmed`() {
        val state = Task126ReviewInteractionFixtures.accountSwitchDirty()

        val outcome = Task126ReviewInteractionReducer.apply(Task126UserChoice.ExportBackup, state)

        assertEquals("account=A;backupExported=true;pending=3", outcome.observedLocalResult)
        assertEquals("blockedCrossAccountPush=true", outcome.observedSyncResult)
        assertEquals(3, outcome.pendingAfter)
    }
}
