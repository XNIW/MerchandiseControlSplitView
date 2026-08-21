package com.example.merchandisecontrolsplitview.data

import com.example.merchandisecontrolsplitview.viewmodel.countStorefrontImportDifferences
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorefrontAuthoringContractTest {
    @Test
    fun `mutation payload is allowlisted and never contains operational fields`() {
        val payload = storefrontMutationPayload(
            sourceProductId = "11111111-1111-1111-1111-111111111111",
            operation = StorefrontMutationOperation.PUBLISH,
            draft = StorefrontEditorDraft(
                publicName = "Public tea",
                publicDescription = "Visible description",
                storefrontCategoryId = "22222222-2222-2222-2222-222222222222",
                publicPrice = 1_990,
                pickupEnabled = true
            )
        )

        assertEquals("1990", payload.getValue("publicPrice").jsonPrimitive.content)
        assertEquals(
            setOf(
                "sourceProductId", "publicName", "publicDescription",
                "storefrontCategoryId", "publicBrand", "publicPrice",
                "compareAtPrice", "priceSourceMode", "promotionStartsAt",
                "promotionEndsAt", "featured", "homeOrder", "pickupEnabled",
                "deliveryEnabled", "reservationEnabled", "availability",
                "publicImageId"
            ),
            payload.keys
        )
        listOf(
            "purchasePrice", "cost", "margin", "supplier", "stockQuantity",
            "warehouseLocation", "internalNotes", "priceHistory", "audit",
            "remoteRef", "staffIdentity", "barcode", "taxData"
        ).forEach { forbidden -> assertFalse(payload.containsKey(forbidden)) }
    }

    @Test
    fun `hide payload carries only stable remote product identity`() {
        val payload = storefrontMutationPayload(
            sourceProductId = "11111111-1111-1111-1111-111111111111",
            operation = StorefrontMutationOperation.HIDE,
            draft = StorefrontEditorDraft(publicName = "Ignored", publicPrice = 10)
        )

        assertEquals(setOf("sourceProductId"), payload.keys)
    }

    @Test
    fun `public preview projection excludes every internal field`() {
        val preview = publication().toPublicPreviewPayload()

        assertEquals(17, preview.size)
        assertTrue(preview["description"] === JsonNull)
        listOf(
            "sourceProductId", "purchasePrice", "cost", "margin", "supplier",
            "stockQuantity", "warehouseLocation", "internalNotes", "priceHistory",
            "audit", "remoteRef", "staffIdentity", "barcode", "taxData"
        ).forEach { forbidden -> assertFalse(preview.containsKey(forbidden)) }
    }

    @Test
    fun `database statuses map to bounded mobile state machine`() {
        assertEquals(StorefrontPublicationStatus.DRAFT, StorefrontPublicationStatus.fromWire("draft"))
        assertEquals(StorefrontPublicationStatus.SCHEDULED, StorefrontPublicationStatus.fromWire("scheduled"))
        assertEquals(StorefrontPublicationStatus.PUBLISHED, StorefrontPublicationStatus.fromWire("published"))
        assertEquals(StorefrontPublicationStatus.HIDDEN, StorefrontPublicationStatus.fromWire("paused"))
        assertEquals(StorefrontPublicationStatus.ARCHIVED, StorefrontPublicationStatus.fromWire("ended"))
        assertEquals(StorefrontPublicationStatus.UNPUBLISHED, StorefrontPublicationStatus.fromWire(null))
    }

    @Test
    fun `barcode is never accepted as Storefront remote identity`() {
        assertFalse(isStorefrontRemoteIdentity("780000000001"))
        assertTrue(isStorefrontRemoteIdentity("11111111-1111-1111-1111-111111111111"))
    }

    @Test
    fun `operational import contract cannot carry or overwrite Storefront publication`() {
        val importFields = ImportApplyRequest::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(importFields.none { field ->
            field.contains("storefront") || field.contains("publication") || field.contains("publicprice")
        })
        val publicDraft = StorefrontEditorDraft.fromPublication(publication())
        val importedOperationalProduct = Product(
            barcode = "780000000001",
            productName = "Imported internal name",
            retailPrice = 4_990.0
        )

        assertEquals(1_990L, publicDraft.publicPrice)
        assertEquals("Public tea", publicDraft.publicName)
        assertEquals(4_990.0, importedOperationalProduct.retailPrice)
    }

    @Test
    fun `post import summary counts divergence without changing public price`() {
        val localId = 42L
        val remoteId = "11111111-1111-1111-1111-111111111111"
        val old = Product(
            id = localId,
            barcode = "780000000001",
            productName = "Public tea",
            retailPrice = 1_990.0
        )
        val updated = old.copy(retailPrice = 4_990.0)
        val summary = StorefrontPublicationListSummary(
            sourceProductId = remoteId,
            status = "published",
            publicName = "Public tea",
            publicPrice = 1_990
        )

        assertEquals(
            1,
            countStorefrontImportDifferences(
                updatedProducts = listOf(ProductUpdate(old, updated, listOf(1))),
                remoteIds = mapOf(localId to remoteId),
                summariesByRemoteId = mapOf(remoteId to summary)
            )
        )
        assertEquals(1_990L, summary.publicPrice)
    }

    private fun publication() = StorefrontPublication(
        publicationId = "33333333-3333-3333-3333-333333333333",
        sourceProductId = "11111111-1111-1111-1111-111111111111",
        status = "published",
        publicName = "Public tea",
        publicPrice = 1_990,
        version = 3,
        updatedAt = "2026-08-21T12:00:00Z"
    )
}
