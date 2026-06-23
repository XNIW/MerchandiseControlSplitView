package com.example.merchandisecontrolsplitview.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.query.Order
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

    override suspend fun fetchAllSessionsForOwner(shopId: String?): Result<List<SharedSheetSessionRecord>> =
        if (shopId.isNullOrBlank()) {
            fetchAllSessionsForOwner()
        } else {
            runCatching {
                fetchAllPagesByIndexedRange(
                    pageSize = INVENTORY_REMOTE_PAGE_SIZE,
                    maxPageIterations = INVENTORY_REMOTE_PAGE_FETCH_MAX_ITERATIONS,
                    tableLabel = "shared_sheet_sessions shop-scoped"
                ) { from, to ->
                    requireClient().postgrest["shared_sheet_sessions"].select {
                        filter {
                            eq("shop_id", shopId)
                        }
                        order("remote_id", Order.ASCENDING)
                        range(from, to)
                    }.decodeList()
                }
            }
        }

    override suspend fun fetchSessionsByRemoteIds(remoteIds: Set<String>): Result<List<SharedSheetSessionRecord>> =
        runCatching {
            if (remoteIds.isEmpty()) return@runCatching emptyList()
            requireClient().postgrest["shared_sheet_sessions"].select {
                filter {
                    isIn("remote_id", remoteIds.map(::canonicalSessionRemoteId).sorted())
                }
                order("remote_id", Order.ASCENDING)
            }.decodeList()
        }

    override suspend fun fetchSessionsByRemoteIds(
        remoteIds: Set<String>,
        shopId: String?
    ): Result<List<SharedSheetSessionRecord>> =
        if (shopId.isNullOrBlank()) {
            fetchSessionsByRemoteIds(remoteIds)
        } else {
            runCatching {
                if (remoteIds.isEmpty()) return@runCatching emptyList()
                requireClient().postgrest["shared_sheet_sessions"].select {
                    filter {
                        isIn("remote_id", remoteIds.map(::canonicalSessionRemoteId).sorted())
                        eq("shop_id", shopId)
                    }
                    order("remote_id", Order.ASCENDING)
                }.decodeList()
            }
        }

    override suspend fun upsertSessions(rows: List<SharedSheetSessionUpsertRow>): Result<Unit> =
        runCatching {
            if (rows.isEmpty()) return@runCatching
            requireClient().postgrest["shared_sheet_sessions"].upsert(rows) {
                onConflict = "remote_id"
            }
        }

    override suspend fun upsertSessions(rows: List<SharedSheetSessionUpsertRow>, shopId: String?): Result<Unit> =
        upsertSessions(rows.map { it.copy(shopId = shopId) })
}
