package com.example.merchandisecontrolsplitview.ui.screens

import androidx.paging.LoadState
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.productimage.ProductImageMutationPhase
import com.example.merchandisecontrolsplitview.viewmodel.ProductImageUiState
import com.example.merchandisecontrolsplitview.viewmodel.ProductImageUiStatus
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossPlatformReliabilityPresentationTest {

    @Test
    fun `refresh failure without cached rows is an error and never an empty catalog`() {
        val state = productPagingPresentation(
            itemCount = 0,
            refreshState = LoadState.Error(IOException("offline")),
            appendState = LoadState.NotLoading(endOfPaginationReached = false)
        )

        assertEquals(ProductPagingPrimaryState.ERROR, state.primaryState)
        assertFalse(state.showRefreshError)
    }

    @Test
    fun `successful empty refresh is the only empty catalog state`() {
        val state = productPagingPresentation(
            itemCount = 0,
            refreshState = LoadState.NotLoading(endOfPaginationReached = true),
            appendState = LoadState.NotLoading(endOfPaginationReached = true)
        )

        assertEquals(ProductPagingPrimaryState.EMPTY, state.primaryState)
        assertFalse(state.showRefreshError)
        assertFalse(state.showAppendError)
    }

    @Test
    fun `refresh and append failures preserve cached products with non destructive retry`() {
        val refreshFailure = productPagingPresentation(
            itemCount = 5,
            refreshState = LoadState.Error(IOException("refresh")),
            appendState = LoadState.NotLoading(endOfPaginationReached = false)
        )
        val appendFailure = productPagingPresentation(
            itemCount = 5,
            refreshState = LoadState.NotLoading(endOfPaginationReached = false),
            appendState = LoadState.Error(IOException("append"))
        )

        assertEquals(ProductPagingPrimaryState.CONTENT, refreshFailure.primaryState)
        assertTrue(refreshFailure.showRefreshError)
        assertEquals(ProductPagingPrimaryState.CONTENT, appendFailure.primaryState)
        assertTrue(appendFailure.showAppendError)
    }

    @Test
    fun `paging recovery returns from error to content or empty success`() {
        val failed = productPagingPresentation(
            itemCount = 0,
            refreshState = LoadState.Error(IOException("temporary")),
            appendState = LoadState.NotLoading(endOfPaginationReached = false)
        )
        val recovered = productPagingPresentation(
            itemCount = 2,
            refreshState = LoadState.NotLoading(endOfPaginationReached = false),
            appendState = LoadState.NotLoading(endOfPaginationReached = false)
        )

        assertEquals(ProductPagingPrimaryState.ERROR, failed.primaryState)
        assertEquals(ProductPagingPrimaryState.CONTENT, recovered.primaryState)
    }

    @Test
    fun `scanner opens an existing product directly and keeps new barcode creation distinct`() {
        val existing = Product(
            id = 42L,
            barcode = "780000000042",
            productName = "Existing"
        )

        assertEquals(
            ScannedBarcodeDestination.ExistingProduct(existing),
            scannedBarcodeDestination(existing.barcode, existing)
        )
        assertEquals(
            ScannedBarcodeDestination.NewProduct("780000000099"),
            scannedBarcodeDestination("780000000099", null)
        )
    }

    @Test
    fun `save and dismiss remain blocked throughout cancellable image upload phases`() {
        for (phase in listOf(
            ProductImageMutationPhase.PREPROCESSING,
            ProductImageMutationPhase.UPLOAD_MAIN,
            ProductImageMutationPhase.UPLOAD_THUMB
        )) {
            assertTrue(
                productImageMutationBusy(
                    mainState = ProductImageUiState(
                        status = ProductImageUiStatus.UPLOADING,
                        mutationPhase = phase
                    ),
                    thumbState = null
                )
            )
        }
        assertFalse(
            productImageMutationBusy(
                mainState = ProductImageUiState(ProductImageUiStatus.READY),
                thumbState = null
            )
        )
    }

    @Test
    fun `main failure keeps retry state while thumbnail remains available for preview`() {
        val thumbBytes = byteArrayOf(1, 2, 3)
        val state = productImagePreviewState(
            mainState = ProductImageUiState(
                status = ProductImageUiStatus.ERROR,
                errorCode = "main_download_failed"
            ),
            thumbState = ProductImageUiState(
                status = ProductImageUiStatus.READY,
                bytes = thumbBytes
            )
        )

        assertEquals(ProductImageUiStatus.ERROR, state?.status)
        assertEquals("main_download_failed", state?.errorCode)
        assertTrue(state?.bytes?.contentEquals(thumbBytes) == true)
    }
}
