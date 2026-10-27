package com.example.merchandisecontrolsplitview.data

import java.math.BigDecimal
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Bounded value observations only: no query, mutation, clock, authority or ACK inference. */
internal object PilotB6Values {
    private const val MAX_BYTES = 16_384
    private fun text(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun number(value: Double?): JsonElement {
        if (value == null) return JsonNull
        require(value.isFinite()) { "B6_NONFINITE_NUMBER" }
        return JsonPrimitive(BigDecimal.valueOf(value).stripTrailingZeros().toPlainString())
    }
    private fun id(value: String?): JsonElement {
        if (value == null) return JsonNull
        require(value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) { "B6_INVALID_UUID" }
        return JsonPrimitive(UUID.fromString(value).toString())
    }
    private fun type(value: String): JsonElement {
        val canonical = value.lowercase(Locale.ROOT)
        require(canonical == "purchase" || canonical == "retail") { "B6_INVALID_PRICE_TYPE" }
        return JsonPrimitive(canonical)
    }
    private fun grid(rows: List<List<String>>): JsonElement = JsonArray(rows.map { row -> JsonArray(row.map(::JsonPrimitive)) })
    private fun flags(values: List<Boolean>): JsonElement = JsonArray(values.map(::JsonPrimitive))
    private fun encode(vararg fields: Pair<String, JsonElement>): String {
        val encoded = JsonObject(fields.toMap().toSortedMap()).toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "B6_VALUE_BYTES_EXCEEDED" }
        return encoded
    }
    fun product(row: InventoryProductRow): String = encode(
        "barcode" to text(row.barcode), "itemNumber" to text(row.itemNumber), "productName" to text(row.productName),
        "secondProductName" to text(row.secondProductName), "purchasePrice" to number(row.purchasePrice),
        "retailPrice" to number(row.retailPrice), "supplierID" to id(row.supplierId), "categoryID" to id(row.categoryId),
        "stockQuantity" to number(row.stockQuantity)
    )
    fun price(row: InventoryProductPriceRow): String = encode(
        "productID" to id(row.productId), "type" to type(row.type), "price" to number(row.price),
        "effectiveAt" to text(row.effectiveAt), "createdAt" to text(row.createdAt),
        "source" to text(row.source), "note" to text(row.note)
    )
    /** Local keys are deliberately not substituted for remote identity. Caller must prove the ref join. */
    fun localPrice(row: ProductPrice): String = encode(
        "productID" to JsonNull, "type" to type(row.type), "price" to number(row.price),
        "effectiveAt" to text(row.effectiveAt), "createdAt" to text(row.createdAt),
        "source" to text(row.source), "note" to text(row.note)
    )
    fun history(row: SharedSheetSessionRecord): String = historyValues(
        id(row.remoteId), row.payloadVersion, row.displayName, row.timestamp, row.supplier, row.category,
        row.isManualEntry, row.data, row.sessionOverlay?.editable, row.sessionOverlay?.complete, row.deletedAt
    )
    fun history(row: SharedSheetSessionUpsertRow): String = historyValues(
        id(row.remoteId), row.payloadVersion, row.displayName, row.timestamp, row.supplier, row.category,
        row.isManualEntry, row.data, row.sessionOverlay.editable, row.sessionOverlay.complete, row.deletedAt
    )
    /** Full actual Room values, with the absent remote UUID explicit. No remote ID is synthesized. */
    fun localHistory(entry: HistoryEntry): String = historyValues(
        JsonNull, SESSION_PAYLOAD_VERSION, entry.displayName, entry.timestamp, entry.supplier, entry.category,
        entry.isManualEntry, entry.data, entry.editable, entry.complete, entry.deletedAt
    )
    private fun historyValues(remoteID: JsonElement, payloadVersion: Int, displayName: String?, timestamp: String,
        supplier: String, category: String, isManualEntry: Boolean, data: List<List<String>>,
        editable: List<List<String>>?, complete: List<Boolean>?, deletedAt: String?): String = encode(
        "remoteID" to remoteID, "payloadVersion" to JsonPrimitive(payloadVersion), "displayName" to text(displayName),
        "timestamp" to text(timestamp), "supplier" to text(supplier), "category" to text(category),
        "isManualEntry" to JsonPrimitive(isManualEntry), "data" to grid(data),
        "editable" to (editable?.let(::grid) ?: JsonNull), "complete" to (complete?.let(::flags) ?: JsonNull),
        "deletedAt" to text(deletedAt)
    )
}
