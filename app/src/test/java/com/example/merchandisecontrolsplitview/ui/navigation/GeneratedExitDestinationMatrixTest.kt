package com.example.merchandisecontrolsplitview.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GeneratedExitDestinationMatrixTest {

    @Test
    fun flow_a_new_excel_generated_finish_returns_to_new_excel_destination() {
        assertEquals(
            GeneratedExitDestination.NewExcelDestination,
            GeneratedExitDestinationResolver.resolve(
                GeneratedExitRequest(
                    origin = ImportNavOrigin.HOME,
                    exitReason = GeneratedExitReason.Done,
                    currentRoute = Screen.Generated.route,
                    entryUid = 11L,
                    isNewEntry = true
                )
            )
        )
    }

    @Test
    fun flow_b_new_excel_import_success_returns_to_new_excel_destination() {
        assertEquals(
            GeneratedExitDestination.NewExcelDestination,
            GeneratedExitDestinationResolver.resolve(
                GeneratedExitRequest(
                    origin = ImportNavOrigin.HOME,
                    exitReason = GeneratedExitReason.ImportSuccess,
                    currentRoute = Screen.ImportAnalysis.route,
                    entryUid = 11L,
                    isNewEntry = true,
                    previewId = 1L
                )
            )
        )
    }

    @Test
    fun flow_c_history_generated_finish_returns_to_history_root() {
        assertEquals(
            GeneratedExitDestination.HistoryRoot,
            GeneratedExitDestinationResolver.resolve(
                GeneratedExitRequest(
                    origin = ImportNavOrigin.HISTORY,
                    exitReason = GeneratedExitReason.Done,
                    currentRoute = Screen.Generated.route,
                    entryUid = 22L
                )
            )
        )
    }

    @Test
    fun flow_d_history_import_success_returns_to_history_root() {
        assertEquals(
            GeneratedExitDestination.HistoryRoot,
            GeneratedExitDestinationResolver.resolve(
                GeneratedExitRequest(
                    origin = ImportNavOrigin.HISTORY,
                    exitReason = GeneratedExitReason.ImportSuccess,
                    currentRoute = Screen.ImportAnalysis.route,
                    entryUid = 22L,
                    previewId = 2L
                )
            )
        )
    }

    @Test
    fun flow_e_history_import_cancel_returns_to_generated_when_entry_is_available() {
        assertEquals(
            GeneratedExitDestination.Generated(
                entryUid = 22L,
                isNewEntry = false,
                isManualEntry = false
            ),
            GeneratedExitDestinationResolver.resolve(
                GeneratedExitRequest(
                    origin = ImportNavOrigin.HISTORY,
                    exitReason = GeneratedExitReason.ImportCancel,
                    currentRoute = Screen.ImportAnalysis.route,
                    entryUid = 22L,
                    isNewEntry = false,
                    isManualEntry = false,
                    previewId = 2L
                )
            )
        )
    }

    @Test
    fun flow_e_history_correct_rows_returns_to_generated_when_entry_is_available() {
        assertEquals(
            GeneratedExitDestination.Generated(
                entryUid = 22L,
                isNewEntry = false,
                isManualEntry = false
            ),
            GeneratedExitDestinationResolver.resolve(
                GeneratedExitRequest(
                    origin = ImportNavOrigin.HISTORY,
                    exitReason = GeneratedExitReason.CorrectRows,
                    currentRoute = Screen.ImportAnalysis.route,
                    entryUid = 22L,
                    isNewEntry = false,
                    isManualEntry = false,
                    previewId = 2L
                )
            )
        )
    }

    @Test
    fun flow_f_history_tab_always_opens_history_root_from_child_route() {
        assertEquals(
            GeneratedExitDestination.HistoryRoot,
            GeneratedExitDestinationResolver.resolve(
                GeneratedExitRequest(
                    origin = ImportNavOrigin.HOME,
                    exitReason = GeneratedExitReason.HistoryTabSelected,
                    currentRoute = Screen.Generated.route,
                    entryUid = 33L
                )
            )
        )
    }
}
