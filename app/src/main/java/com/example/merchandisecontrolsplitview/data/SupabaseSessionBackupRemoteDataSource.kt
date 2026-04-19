package com.example.merchandisecontrolsplitview.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

/**
 * Implementazione PostgREST per [SessionBackupRemoteDataSource] (task 023).
 */
class SupabaseSessionBackupRemoteDataSource(
    private val client: SupabaseClient?,
) : SessionBackupRemoteDataSource {

    override val isConfigured: Boolean get() = client != null

    private fun requireClient(): SupabaseClient =
        client ?: error("Supabase non configurato")

    override suspend fun fetchAllSessionsForOwner(): Result<List<SharedSheetSessionRecord>> =
        runCatching {
            requireClient().postgrest.fetchSharedSheetSessionsAllPagesOrderedByRemoteId()
        }

    override suspend fun upsertSessions(rows: List<SharedSheetSessionUpsertRow>): Result<Unit> =
        runCatching {
            if (rows.isEmpty()) return@runCatching
            requireClient().postgrest["shared_sheet_sessions"].upsert(rows) {
                onConflict = "remote_id"
            }
        }
}
