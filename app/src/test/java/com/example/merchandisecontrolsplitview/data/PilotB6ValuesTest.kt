package com.example.merchandisecontrolsplitview.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class PilotB6ValuesTest {
    private val id = "10000000-0000-4000-8000-000000000001"
    private val rawTime = "2026-10-23T12:34:56.123456+00:00"
    private fun product() = InventoryProductRow(id, id, barcode = "B6", purchasePrice = 12.123456789, stockQuantity = null)
    @Test fun productHasExactNineFieldsExplicitNullAndUnroundedDecimal() {
        val values = Json.parseToJsonElement(PilotB6Values.product(product())).jsonObject
        assertEquals(9, values.size)
        assertEquals("12.123456789", values.getValue("purchasePrice").jsonPrimitive.content)
        assertEquals(JsonNull, values["stockQuantity"]); assertEquals(JsonNull, values["supplierID"])
        assertFalse(values.containsKey("updatedAt")); assertFalse(values.containsKey("ownerUserID"))
    }
    @Test fun zeroHasOneNumericMeaningWithoutConvertingNullToZero() {
        val values = Json.parseToJsonElement(PilotB6Values.product(product().copy(purchasePrice = -0.0))).jsonObject
        assertEquals("0", values.getValue("purchasePrice").jsonPrimitive.content)
        assertEquals(JsonNull, values["retailPrice"])
    }
    @Test fun nonfiniteCannotEnterProof() { assertThrows(IllegalArgumentException::class.java) { PilotB6Values.product(product().copy(retailPrice = Double.NaN)) } }
    @Test fun malformedRemoteReferenceCannotBeRewrittenToValidIdentity() { assertThrows(IllegalArgumentException::class.java) { PilotB6Values.product(product().copy(supplierId = "1-1-1-1-1")) } }
    @Test fun priceKeepsActualTimestampPrecisionAndNoteValues() {
        val row = InventoryProductPriceRow(id, id, productId = id, type = "RETAIL", price = 1.23456,
            effectiveAt = rawTime, createdAt = rawTime, note = " exact ")
        val values = Json.parseToJsonElement(PilotB6Values.price(row)).jsonObject
        assertEquals("retail", values.getValue("type").jsonPrimitive.content)
        assertEquals(rawTime, values.getValue("effectiveAt").jsonPrimitive.content)
        assertEquals("1.23456", values.getValue("price").jsonPrimitive.content)
        assertEquals(" exact ", values.getValue("note").jsonPrimitive.content)
    }
    @Test fun localPriceNeverClaimsLocalProductKeyIsRemoteUUID() {
        val values = Json.parseToJsonElement(PilotB6Values.localPrice(ProductPrice(productId = 42, type = "PURCHASE", price = 2.0, effectiveAt = rawTime))).jsonObject
        assertEquals(JsonNull, values["productID"])
    }
    @Test fun exactHistoryUpsertAndReadValuesMatchIncludingOverlay() {
        val overlay = SessionOverlay(editable = listOf(listOf("3")), complete = listOf(true))
        val upsert = SharedSheetSessionUpsertRow(id, 2, "Title", rawTime, "S", "C", false,
            listOf(listOf("B6")), overlay, id)
        val record = SharedSheetSessionRecord(id, 2, "Title", rawTime, "S", "C", false,
            listOf(listOf("B6")), overlay, updatedAt = "unrelated metadata")
        assertEquals(PilotB6Values.history(upsert), PilotB6Values.history(record))
        assertNotEquals(PilotB6Values.history(upsert), PilotB6Values.history(record.copy(sessionOverlay = overlay.copy(complete = listOf(false)))))
    }
    @Test fun localHistoryIncludesFullPayloadWithoutFabricatingRemoteID() {
        val local = HistoryEntry(uid = 12, id = "export", timestamp = rawTime, data = listOf(listOf("B6")),
            editable = listOf(listOf("2")), complete = listOf(true), displayName = "Title")
        val values = Json.parseToJsonElement(PilotB6Values.localHistory(local)).jsonObject
        assertEquals(JsonNull, values["remoteID"]); assertEquals("2", values.getValue("payloadVersion").jsonPrimitive.content)
        assertFalse(values.containsKey("uid")); assertTrue(values.containsKey("data")); assertTrue(values.containsKey("editable")); assertTrue(values.containsKey("complete"))
    }
    @Test fun absentOverlayRemainsAbsentInsteadOfInventingEmptyArrays() {
        val record = SharedSheetSessionRecord(id, 1, "Title", rawTime, "", "", false, emptyList())
        val values = Json.parseToJsonElement(PilotB6Values.history(record)).jsonObject
        assertEquals(JsonNull, values["editable"]); assertEquals(JsonNull, values["complete"])
    }
    @Test fun boundUsesUtf8BytesAndRejectsOverflow() {
        assertThrows(IllegalArgumentException::class.java) { PilotB6Values.product(product().copy(productName = "界".repeat(6000))) }
    }
}
