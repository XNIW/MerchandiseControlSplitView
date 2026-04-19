package com.example.merchandisecontrolsplitview.data

import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Order

/**
 * Task 022 — paginazione offset deterministica per letture complete su `inventory_*` e `inventory_product_prices`.
 *
 * ## PAGE_SIZE (reale vs default Supabase)
 * I progetti Supabase ospitati usano tipicamente **max_rows = 1000** per PostgREST (risposta massima per select).
 * Qui **PAGE_SIZE = 900** → **margine 100** sotto il default, evitando valori al limite e troncamenti silenziosi.
 * Se il progetto remoto ha `max_rows` diverso, allineare questa costante e documentarlo nel task workspace.
 *
 * ## Ordine DSL Supabase Kotlin 3.5.x (postgrest-kt)
 * In [io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder]:
 * - [order] accoda il parametro URL `order=...` (colonna.asc/desc.nullsfirst|nullslast).
 * - [range] imposta `offset` e `limit` dove **limit = to - from + 1** (coerente con PostgREST Range).
 * Entrambe le chiamate scrivono solo nella mappa `params` del builder;
 * **l'ordine consigliato e' `order("id", ASCENDING)` prima di `range(from, to)`**
 * per chiarezza (stessa richiesta HTTP).
 *
 * @see io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder.order
 * @see io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder.range
 */
internal const val INVENTORY_REMOTE_PAGE_SIZE: Long = 900L

/** Fail-safe D-022-P3: non è terminazione normale; se scatta → errore esplicito. */
internal const val INVENTORY_REMOTE_PAGE_FETCH_MAX_ITERATIONS: Int = 50_000

/**
 * Loop offset unico: `from = pageIndex * pageSize`, `to = from + pageSize - 1` (D-022-P2 / P2OFF).
 * Pagina intermedia in errore: non catturare qui — propagare da [fetchPage] (D-022-PAGEERR).
 */
internal suspend fun <T> fetchAllPagesByIndexedRange(
    pageSize: Long,
    maxPageIterations: Int,
    tableLabel: String,
    fetchPage: suspend (from: Long, to: Long) -> List<T>
): List<T> {
    require(pageSize >= 1L) { "pageSize must be >= 1" }
    require(maxPageIterations >= 1) { "maxPageIterations must be >= 1" }
    val out = ArrayList<T>()
    var pageIndex = 0
    while (pageIndex < maxPageIterations) {
        val from = pageIndex * pageSize
        val to = from + pageSize - 1
        val page = fetchPage(from, to)
        when {
            page.isEmpty() -> return out
            page.size.toLong() < pageSize -> {
                out.addAll(page)
                return out
            }
            else -> {
                out.addAll(page)
                pageIndex++
            }
        }
    }
    error(
        "inventory remote fetch exceeded max page iterations ($maxPageIterations) for $tableLabel " +
            "(fail-safe D-022-P3 — not normal end-of-dataset)"
    )
}

internal suspend inline fun <reified T : Any> Postgrest.fetchInventoryTableAllPagesOrderedById(table: String): List<T> =
    fetchAllPagesByIndexedRange(
        pageSize = INVENTORY_REMOTE_PAGE_SIZE,
        maxPageIterations = INVENTORY_REMOTE_PAGE_FETCH_MAX_ITERATIONS,
        tableLabel = table
    ) { from, to ->
        this[table].select {
            order("id", Order.ASCENDING)
            range(from, to)
        }.decodeList()
    }

/**
 * Fetch paginato di `shared_sheet_sessions` ordinato per PK testuale (`remote_id`),
 * coerente con lo spirito task 022 (range + order deterministici). RLS limita all'owner JWT.
 */
internal suspend inline fun <reified T : Any> Postgrest.fetchSharedSheetSessionsAllPagesOrderedByRemoteId(): List<T> =
    fetchAllPagesByIndexedRange(
        pageSize = INVENTORY_REMOTE_PAGE_SIZE,
        maxPageIterations = INVENTORY_REMOTE_PAGE_FETCH_MAX_ITERATIONS,
        tableLabel = "shared_sheet_sessions"
    ) { from, to ->
        this["shared_sheet_sessions"].select {
            order("remote_id", Order.ASCENDING)
            range(from, to)
        }.decodeList()
    }
