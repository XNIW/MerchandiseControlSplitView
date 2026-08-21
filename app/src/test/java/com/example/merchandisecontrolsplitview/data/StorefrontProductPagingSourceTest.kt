package com.example.merchandisecontrolsplitview.data

import androidx.paging.PagingSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorefrontProductPagingSourceTest {
    @Test
    fun `large catalog loads one bounded summary page at a time`() = runTest {
        val repository = mockk<InventoryRepository>()
        val remote = RecordingSummaryRemote(totalPages = 197)
        coEvery { repository.getProductsWithDetailsByRemoteIds(any()) } returns emptyList()
        val paging = StorefrontProductPagingSource(
            remote = remote,
            repository = repository,
            shopId = SHOP_ID,
            filter = StorefrontSummaryFilter.PUBLISHED,
            query = "tea"
        )

        val first = paging.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 20,
                placeholdersEnabled = false
            )
        )
        assertTrue(first is PagingSource.LoadResult.Page)
        first as PagingSource.LoadResult.Page
        assertNull(first.prevKey)
        assertEquals(2, first.nextKey)
        assertEquals(listOf(1), remote.pages)
        assertEquals(listOf(100), remote.pageSizes)
        coVerify(exactly = 1) { repository.getProductsWithDetailsByRemoteIds(any()) }

        val second = paging.load(
            PagingSource.LoadParams.Append(
                key = 2,
                loadSize = 20,
                placeholdersEnabled = false
            )
        )
        assertTrue(second is PagingSource.LoadResult.Page)
        assertEquals(listOf(1, 2), remote.pages)
        assertEquals(listOf(100, 100), remote.pageSizes)
    }

    private class RecordingSummaryRemote(
        private val totalPages: Int
    ) : StorefrontAuthoringRemoteDataSource {
        override val isConfigured = true
        val pages = mutableListOf<Int>()
        val pageSizes = mutableListOf<Int>()

        override suspend fun read(
            shopId: String,
            sourceProductIds: List<String>?,
            status: StorefrontPublicationStatus?,
            page: Int
        ): Result<StorefrontAuthoringReadResponse> = error("editor read not expected")

        override suspend fun mutate(
            shopId: String,
            sourceProductId: String,
            operation: StorefrontMutationOperation,
            draft: StorefrontEditorDraft,
            expectedVersion: Long,
            idempotencyKey: String
        ): Result<StorefrontAuthoringMutationResponse> = error("mutation not expected")

        override suspend fun readSummary(
            shopId: String,
            filter: StorefrontSummaryFilter,
            query: String?,
            sourceProductIds: List<String>?,
            page: Int,
            pageSize: Int
        ): Result<StorefrontAuthoringSummaryResponse> {
            pages += page
            pageSizes += pageSize
            val rows = (1..100).map { index ->
                StorefrontPublicationListSummary(
                    sourceProductId = remoteId((page - 1) * 100 + index),
                    status = "published",
                    publicName = "Public $index",
                    publicPrice = 1_990,
                    version = 1
                )
            }
            return Result.success(
                StorefrontAuthoringSummaryResponse(
                    ok = true,
                    code = "success",
                    rows = rows,
                    pagination = StorefrontPagination(
                        page = page,
                        pageSize = pageSize,
                        total = totalPages * 100,
                        totalPages = totalPages
                    )
                )
            )
        }
    }

    private companion object {
        const val SHOP_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        fun remoteId(index: Int): String =
            "dddddddd-dddd-dddd-dddd-${index.toString().padStart(12, '0')}"
    }
}
