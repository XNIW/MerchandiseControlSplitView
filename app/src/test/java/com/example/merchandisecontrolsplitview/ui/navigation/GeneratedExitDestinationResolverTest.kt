package com.example.merchandisecontrolsplitview.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GeneratedExitDestinationResolverTest {

    @Test
    fun missing_preview_uses_safe_recoverable_fallback_for_history() {
        val destination = GeneratedExitDestinationResolver.resolve(
            GeneratedExitRequest(
                origin = ImportNavOrigin.HISTORY,
                exitReason = GeneratedExitReason.MissingPreview,
                currentRoute = Screen.ImportAnalysis.route,
                entryUid = 42L,
                previewId = 7L
            )
        )

        assertEquals(
            GeneratedExitDestination.RecoverableError(GeneratedExitDestination.HistoryRoot),
            destination
        )
    }

    @Test
    fun missing_preview_uses_new_excel_fallback_for_home() {
        val destination = GeneratedExitDestinationResolver.resolve(
            GeneratedExitRequest(
                origin = ImportNavOrigin.HOME,
                exitReason = GeneratedExitReason.MissingPreview,
                currentRoute = Screen.ImportAnalysis.route,
                previewId = 7L
            )
        )

        assertEquals(
            GeneratedExitDestination.RecoverableError(
                GeneratedExitDestination.NewExcelDestination
            ),
            destination
        )
    }

    @Test
    fun missing_session_from_generated_without_entry_uid_is_recoverable() {
        val destination = GeneratedExitDestinationResolver.resolve(
            GeneratedExitRequest(
                origin = ImportNavOrigin.GENERATED,
                exitReason = GeneratedExitReason.MissingSession,
                currentRoute = Screen.Generated.route
            )
        )

        assertEquals(
            GeneratedExitDestination.RecoverableError(
                GeneratedExitDestination.NewExcelDestination
            ),
            destination
        )
    }

    @Test
    fun import_cancel_with_entry_uid_returns_to_generated_session() {
        val destination = GeneratedExitDestinationResolver.resolve(
            GeneratedExitRequest(
                origin = ImportNavOrigin.HISTORY,
                exitReason = GeneratedExitReason.ImportCancel,
                currentRoute = Screen.ImportAnalysis.route,
                entryUid = 91L,
                isNewEntry = false,
                isManualEntry = true,
                previewId = 4L
            )
        )

        assertEquals(
            GeneratedExitDestination.Generated(
                entryUid = 91L,
                isNewEntry = false,
                isManualEntry = true
            ),
            destination
        )
    }

    @Test
    fun correct_rows_with_entry_uid_returns_to_generated_session() {
        val destination = GeneratedExitDestinationResolver.resolve(
            GeneratedExitRequest(
                origin = ImportNavOrigin.HISTORY,
                exitReason = GeneratedExitReason.CorrectRows,
                currentRoute = Screen.ImportAnalysis.route,
                entryUid = 91L,
                isNewEntry = false,
                isManualEntry = false,
                previewId = 4L
            )
        )

        assertEquals(
            GeneratedExitDestination.Generated(
                entryUid = 91L,
                isNewEntry = false,
                isManualEntry = false
            ),
            destination
        )
    }

    @Test
    fun import_cancel_without_entry_uid_recovers_instead_of_noop() {
        val destination = GeneratedExitDestinationResolver.resolve(
            GeneratedExitRequest(
                origin = ImportNavOrigin.HISTORY,
                exitReason = GeneratedExitReason.ImportCancel,
                currentRoute = Screen.ImportAnalysis.route,
                previewId = 4L
            )
        )

        assertEquals(
            GeneratedExitDestination.RecoverableError(GeneratedExitDestination.HistoryRoot),
            destination
        )
    }

    @Test
    fun history_origin_done_and_back_resolve_to_history_root() {
        val done = GeneratedExitDestinationResolver.resolve(
            GeneratedExitRequest(
                origin = ImportNavOrigin.HISTORY,
                exitReason = GeneratedExitReason.Done,
                currentRoute = Screen.Generated.route,
                entryUid = 10L
            )
        )
        val back = GeneratedExitDestinationResolver.resolve(
            GeneratedExitRequest(
                origin = ImportNavOrigin.HISTORY,
                exitReason = GeneratedExitReason.SystemBack,
                currentRoute = Screen.Generated.route,
                entryUid = 10L
            )
        )

        assertEquals(GeneratedExitDestination.HistoryRoot, done)
        assertEquals(GeneratedExitDestination.HistoryRoot, back)
    }
}
