package com.example.merchandisecontrolsplitview.data

/**
 * Transport PostgREST per backup/list sessioni history su [shared_sheet_sessions] (task 023).
 * Nessun accesso a Room: solo I/O rete + DTO.
 */
interface SessionBackupRemoteDataSource {

    val isConfigured: Boolean

    /**
     * Tutte le sessioni visibili all'utente autenticato (RLS owner-scoped), paginato lato client.
     */
    suspend fun fetchAllSessionsForOwner(): Result<List<SharedSheetSessionRecord>>

    suspend fun upsertSessions(rows: List<SharedSheetSessionUpsertRow>): Result<Unit>
}
