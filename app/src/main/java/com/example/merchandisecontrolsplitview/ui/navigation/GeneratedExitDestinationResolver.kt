package com.example.merchandisecontrolsplitview.ui.navigation

enum class GeneratedExitReason {
    Done,
    SystemBack,
    ImportCancel,
    CorrectRows,
    ImportSuccess,
    MissingPreview,
    MissingSession,
    HistoryTabSelected
}

data class GeneratedExitRequest(
    val origin: ImportNavOrigin,
    val exitReason: GeneratedExitReason,
    val currentRoute: String? = null,
    val entryUid: Long? = null,
    val isNewEntry: Boolean = false,
    val isManualEntry: Boolean = false,
    val previewId: Long? = null
)

sealed interface GeneratedExitDestination {
    data object NewExcelDestination : GeneratedExitDestination
    data object HistoryRoot : GeneratedExitDestination
    data object DatabaseRoot : GeneratedExitDestination
    data class Generated(
        val entryUid: Long,
        val isNewEntry: Boolean,
        val isManualEntry: Boolean
    ) : GeneratedExitDestination
    data class RecoverableError(
        val fallback: GeneratedExitDestination
    ) : GeneratedExitDestination
}

object GeneratedExitDestinationResolver {
    fun resolve(request: GeneratedExitRequest): GeneratedExitDestination =
        when (request.exitReason) {
            GeneratedExitReason.HistoryTabSelected -> GeneratedExitDestination.HistoryRoot
            GeneratedExitReason.ImportCancel,
            GeneratedExitReason.CorrectRows -> generatedOrRecover(request)
            GeneratedExitReason.MissingPreview,
            GeneratedExitReason.MissingSession -> GeneratedExitDestination.RecoverableError(
                fallback = recoverableFallbackForOrigin(request)
            )
            GeneratedExitReason.Done,
            GeneratedExitReason.SystemBack,
            GeneratedExitReason.ImportSuccess -> destinationForOrigin(request)
        }

    private fun destinationForOrigin(request: GeneratedExitRequest): GeneratedExitDestination =
        when (request.origin) {
            ImportNavOrigin.HOME -> GeneratedExitDestination.NewExcelDestination
            ImportNavOrigin.HISTORY -> GeneratedExitDestination.HistoryRoot
            ImportNavOrigin.DATABASE -> GeneratedExitDestination.DatabaseRoot
            ImportNavOrigin.GENERATED -> generatedOrRecover(request)
        }

    private fun recoverableFallbackForOrigin(request: GeneratedExitRequest): GeneratedExitDestination =
        when (request.origin) {
            ImportNavOrigin.HOME -> GeneratedExitDestination.NewExcelDestination
            ImportNavOrigin.HISTORY -> GeneratedExitDestination.HistoryRoot
            ImportNavOrigin.DATABASE -> GeneratedExitDestination.DatabaseRoot
            ImportNavOrigin.GENERATED -> request.entryUid?.takeIf { it > 0L }?.let { uid ->
                GeneratedExitDestination.Generated(
                    entryUid = uid,
                    isNewEntry = request.isNewEntry,
                    isManualEntry = request.isManualEntry
                )
            } ?: GeneratedExitDestination.NewExcelDestination
        }

    private fun generatedOrRecover(request: GeneratedExitRequest): GeneratedExitDestination {
        val uid = request.entryUid?.takeIf { it > 0L }
        return if (uid != null) {
            GeneratedExitDestination.Generated(
                entryUid = uid,
                isNewEntry = request.isNewEntry,
                isManualEntry = request.isManualEntry
            )
        } else {
            GeneratedExitDestination.RecoverableError(
                fallback = when (request.origin) {
                    ImportNavOrigin.HOME,
                    ImportNavOrigin.GENERATED -> GeneratedExitDestination.NewExcelDestination
                    ImportNavOrigin.HISTORY -> GeneratedExitDestination.HistoryRoot
                    ImportNavOrigin.DATABASE -> GeneratedExitDestination.DatabaseRoot
                }
            )
        }
    }
}
