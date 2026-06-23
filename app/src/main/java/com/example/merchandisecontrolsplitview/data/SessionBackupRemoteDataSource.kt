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

    suspend fun fetchAllSessionsForOwner(shopId: String?): Result<List<SharedSheetSessionRecord>> =
        fetchAllSessionsForOwner()

    suspend fun fetchSessionsByRemoteIds(remoteIds: Set<String>): Result<List<SharedSheetSessionRecord>>

    suspend fun fetchSessionsByRemoteIds(
        remoteIds: Set<String>,
        shopId: String?
    ): Result<List<SharedSheetSessionRecord>> =
        fetchSessionsByRemoteIds(remoteIds)

    suspend fun upsertSessions(rows: List<SharedSheetSessionUpsertRow>): Result<Unit>

    suspend fun upsertSessions(rows: List<SharedSheetSessionUpsertRow>, shopId: String?): Result<Unit> =
        upsertSessions(rows.map { it.copy(shopId = shopId) })
}
