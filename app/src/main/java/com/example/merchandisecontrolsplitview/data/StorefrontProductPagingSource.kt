package com.example.merchandisecontrolsplitview.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException

/**
 * Projection paginata Storefront -> identita' remote locali. Ogni load esegue
 * un solo RPC summary da massimo 100 righe e una query Room bounded; non
 * materializza mai il catalogo completo ne' il payload editor/audit.
 */
class StorefrontProductPagingSource(
    private val remote: StorefrontAuthoringRemoteDataSource,
    private val repository: InventoryRepository,
    private val shopId: String,
    private val filter: StorefrontSummaryFilter,
    private val query: String?
) : PagingSource<Int, ProductWithDetails>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ProductWithDetails> {
        val page = params.key ?: 1
        return try {
            val response = remote.readSummary(
                shopId = shopId,
                filter = filter,
                query = query,
                page = page,
                pageSize = STOREFRONT_SUMMARY_PAGE_SIZE
            ).getOrThrow()
            if (!response.ok) {
                return LoadResult.Error(IllegalStateException(response.code))
            }
            val remoteIds = response.rows.map(StorefrontPublicationListSummary::sourceProductId)
            val localRows = repository.getProductsWithDetailsByRemoteIds(remoteIds)
            LoadResult.Page(
                data = localRows,
                prevKey = page.takeIf { it > 1 }?.minus(1),
                nextKey = page.takeIf { it < response.pagination.totalPages }?.plus(1)
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            LoadResult.Error(error)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, ProductWithDetails>): Int? {
        val anchor = state.anchorPosition ?: return null
        val page = state.closestPageToPosition(anchor) ?: return null
        return page.prevKey?.plus(1) ?: page.nextKey?.minus(1)
    }

    companion object {
        const val STOREFRONT_SUMMARY_PAGE_SIZE = 100
    }
}
