package com.example.merchandisecontrolsplitview.pos

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/** Pure JVM codec tests: no Application bootstrap, database, SDK client or network. */
class PosReadCodecTest {
    @Test fun exactClpPreservesValuesAcrossBinaryFloatBoundary() {
        listOf("9007199254740991", "9007199254740992", "9007199254740993", "-9007199254740993").forEach {
            assertEquals(it, PosWire.signedLong(it).toString())
        }
    }
    @Test fun signedInt64LimitsRoundTripExactly() {
        assertEquals(Long.MIN_VALUE, PosWire.signedLong("-9223372036854775808"))
        assertEquals(Long.MAX_VALUE, PosWire.signedLong("9223372036854775807"))
    }
    @Test fun canonicalMoneyRejectsOverflowExponentFractionsWhitespaceAndNegativeZero() {
        listOf("9223372036854775808", "-9223372036854775809", "1e3", "1.0", " 1", "01", "+1", "-0").forEach { raw ->
            expectFailure { PosWire.signedLong(raw) }
        }
    }
    @Test fun fractionalQuantityAndItsDecimalScaleArePreserved() {
        val quantity = PosWire.quantity("0.125")
        assertEquals(BigDecimal("0.125"), quantity)
        assertEquals("12.340", PosWire.quantity("12.340").toPlainString())
        listOf("NaN", "Infinity", "1e-3", ".5", "01.5").forEach { expectFailure { PosWire.quantity(it) } }
    }
    @Test fun cursorRetainsRawMicrosecondsAndCompositeIdInRequest() {
        val cursor = PosCursor(PosInstant.parse("2026-11-05T12:34:56.123456+00:00"), SALE)
        val params = Json.parseToJsonElement(PosReadCodec.request(PosReadRequest(scope(), PosReadAction.PAGE, period(), cursor = cursor))).jsonObject
        assertEquals(JsonPrimitive(cursor.occurredAt.raw), params["p_before_occurred_at"])
        assertEquals(JsonPrimitive(SALE), params["p_before_sale_id"])
        assertEquals(123456000, cursor.occurredAt.instant.nano)
    }
    @Test fun timestampFinerThanServerMicrosecondsIsRejectedInsteadOfTruncated() {
        expectFailure { PosInstant.parse("2026-11-05T12:34:56.1234567+00:00") }
    }
    @Test fun authorizedSummaryAndFirstPageDecodeTogetherWithoutRecalculatingLedgerNet() {
        val reply = decode() as PosEnvelope.Authorized
        val overview = reply.data as PosReadData.Overview
        assertEquals(70L, overview.summaryDays.single().netRevenueClp)
        assertEquals(-30L, overview.summaryDays.single().refundsClp)
        assertEquals(999L, overview.sales.single().netAmountClp)
        assertEquals("online_only", reply.capability.accessPolicy)
    }
    @Test fun numericJsonAmountIsRejectedEvenWhenSmallEnoughForDouble() {
        expectFailure { decode(root(data = overview(sale().with("net_amount_clp", JsonPrimitive(3))))) }
    }
    @Test fun malformedPageCannotYieldOtherwiseValidSummary() {
        expectFailure { decode(root(data = overview(sale().with("sale_status", JsonPrimitive("paid"))))) }
    }
    @Test fun wellFormedDenialProducesNoFinancialDto() {
        val denied = buildJsonObject { put("contract_version", 1); put("ok", false); put("code", "permission_denied"); put("server_time", TIME); put("data", JsonNull) }
        assertEquals("permission_denied", (decode(denied) as PosEnvelope.Denied).code)
    }
    @Test fun deniedResponseWithFinancialBodyCannotQualifyAsEmptySuccess() {
        val denied = buildJsonObject { put("contract_version", 1); put("ok", false); put("code", "permission_denied"); put("server_time", TIME); put("data", overview()) }
        expectFailure { decode(denied) }
    }
    @Test fun wrongPrincipalShopAndKnownDeviceAreRejectedBeforePublication() {
        listOf("principal_id", "shop_id", "shop_device_id").forEach { field ->
            val changed = serverScope().with(field, JsonPrimitive(OTHER))
            expectFailure { decode(root().with("scope", changed)) }
        }
    }
    @Test fun missingSalesReadOrWrongPrincipalKindCannotExposeData() {
        expectFailure { decode(root().with("capability", capability().with("sales_read", JsonPrimitive(false)))) }
        expectFailure { decode(root().with("scope", serverScope().with("principal_kind", JsonPrimitive("staff")))) }
    }
    @Test fun unsupportedCurrencyNeverRelabelsAClpAmount() {
        expectFailure { decode(root().with("currency_code", JsonPrimitive("USD"))) }
        expectFailure { decode(root(data = overview(sale().with("currency_code", JsonPrimitive("USD"))))) }
    }
    @Test fun viewerMetadataLeakIsRejected() {
        expectFailure { decode(root(data = overview(sale().with("staff_name", JsonPrimitive("Identified staff"))))) }
    }
    @Test fun fiscalStatusIsNotUsedAsSaleStatus() {
        val response = decode(root(data = overview(sale().with("fiscal_status", JsonPrimitive("rejected"))))) as PosEnvelope.Authorized
        assertEquals(PosSaleStatus.ACCEPTED, (response.data as PosReadData.Overview).sales.single().status)
    }
    @Test fun repeatedOrNonAdvancingCompositeCursorIsRejected() {
        val request = PosReadRequest(scope(), PosReadAction.PAGE, period(), cursor = PosCursor(PosInstant.parse(TIME), SALE))
        expectFailure { PosReadCodec.response(root(data = buildJsonObject { put("sales", JsonArray(listOf(sale()))); put("next_cursor", cursor()) }).toString().encodeToByteArray(), request) }
    }
    @Test fun detailDecimalQuantityAndAllAmountsArePreserved() {
        val request = PosReadRequest(scope(), PosReadAction.DETAIL, saleId = SALE)
        val detail = buildJsonObject {
            put("sale", sale().let { JsonObject(it - "time_zone" - "latest_update_at" + ("updated_at" to JsonPrimitive(TIME))) })
            put("lines", JsonArray(listOf(buildJsonObject { put("line_position", 1); put("line_type", "item"); put("product_id", JsonNull); put("item_number", JsonNull); put("barcode", JsonNull); put("product_name", "Fraction"); put("quantity", "0.125"); put("unit_amount_clp", "1"); put("line_amount_clp", "2") })))
        }
        val wire = root(data = detail).with("period", buildJsonObject { put("from_date", JsonNull); put("to_date", JsonNull) })
        val decoded = PosReadCodec.response(wire.toString().encodeToByteArray(), request) as PosEnvelope.Authorized
        val line = (decoded.data as PosReadData.Detail).lines.single()
        assertEquals(BigDecimal("0.125"), line.quantity)
        assertEquals(1L, line.unitAmountClp)
        assertEquals(2L, line.lineAmountClp)
    }
    @Test fun nullFinancialDetailFieldsAndUnknownLineTypesAreRejected() {
        val request = PosReadRequest(scope(), PosReadAction.DETAIL, saleId = SALE)
        val line = buildJsonObject { put("line_position", 1); put("line_type", "item"); put("product_id", JsonNull); put("item_number", JsonNull); put("barcode", JsonNull); put("product_name", "Fraction"); put("quantity", "0.125"); put("unit_amount_clp", "1"); put("line_amount_clp", "2") }
        val invalid = listOf(line.with("quantity", JsonNull), line.with("unit_amount_clp", JsonNull), line.with("line_amount_clp", JsonNull), line.with("line_type", JsonPrimitive("product")), line.with("line_position", JsonPrimitive(0)))
        invalid.forEach { changed ->
            val detail = buildJsonObject { put("sale", sale().let { JsonObject(it - "time_zone" - "latest_update_at" + ("updated_at" to JsonPrimitive(TIME))) }); put("lines", JsonArray(listOf(changed))) }
            val wire = root(data = detail).with("period", buildJsonObject { put("from_date", JsonNull); put("to_date", JsonNull) })
            expectFailure { PosReadCodec.response(wire.toString().encodeToByteArray(), request) }
        }
    }
    @Test fun viewerFilterOptionsCannotExposeOperationalIdentities() {
        val data = buildJsonObject { put("can_filter_operational_metadata", false); put("payment_methods", JsonArray(emptyList())); put("staff", JsonArray(listOf(buildJsonObject { put("id", OTHER); put("name", "Staff") }))); put("devices", JsonArray(emptyList())) }
        val request = PosReadRequest(scope(), PosReadAction.FILTER, period())
        expectFailure { PosReadCodec.response(root(data = data).toString().encodeToByteArray(), request) }
    }
    @Test fun pageWithSameInstantUsesUuidAsTheSecondCursorComponent() {
        val olderId = "40000000-0000-4000-8000-000000000000"
        val second = sale().with("pos_sale_id", JsonPrimitive(olderId))
        val data = buildJsonObject { put("summary_days", JsonArray(listOf(summary()))); put("sales", JsonArray(listOf(sale(), second))); put("next_cursor", cursor(olderId)) }
        val reply = decode(root(data = data)) as PosEnvelope.Authorized
        assertEquals(listOf(SALE, olderId), (reply.data as PosReadData.Overview).sales.map { it.id })
    }

    private fun scope() = PosScope(OWNER, SHOP, "device-owned", 7, 2, DEVICE)
    private fun period() = PosPeriod("2026-11-05", "2026-11-05")
    private fun decode(payload: JsonObject = root()) = PosReadCodec.response(payload.toString().encodeToByteArray(), PosReadRequest(scope(), PosReadAction.OVERVIEW))
    private fun root(data: JsonObject = overview()) = buildJsonObject {
        put("contract_version", 1); put("ok", true); put("code", "ok"); put("server_time", TIME)
        put("scope", serverScope()); put("capability", capability()); put("currency_code", "CLP"); put("time_zone", "America/Santiago")
        put("period", buildJsonObject { put("from_date", "2026-11-05"); put("to_date", "2026-11-05") }); put("data", data)
    }
    private fun serverScope() = buildJsonObject { put("principal_kind", "personal_account"); put("principal_id", OWNER); put("shop_id", SHOP); put("shop_device_id", DEVICE) }
    private fun capability() = buildJsonObject { put("sales_read", true); put("operational_metadata_read", false); put("access_policy", "online_only") }
    private fun overview(row: JsonObject = sale()) = buildJsonObject { put("summary_days", JsonArray(listOf(summary()))); put("sales", JsonArray(listOf(row))); put("next_cursor", cursor()) }
    private fun summary() = buildJsonObject {
        put("shop_id", SHOP); put("business_date", "2026-11-05"); put("currency_code", "CLP"); put("time_zone", "America/Santiago")
        put("gross_sales_clp", "100"); put("discounts_clp", "0"); put("refunds_clp", "-30"); put("net_revenue_clp", "70")
        put("sale_count", 1); put("refund_count", 1); put("void_count", 0); put("transaction_count", 2); put("latest_ledger_at", TIME); put("server_time", TIME)
    }
    private fun sale() = buildJsonObject {
        put("pos_sale_id", SALE); put("sale_number", "sale-owned"); put("occurred_at", TIME); put("business_date", "2026-11-05"); put("business_kind", "sale"); put("sale_status", "accepted"); put("fiscal_status", JsonNull)
        put("gross_amount_clp", "999"); put("discount_amount_clp", "0"); put("net_amount_clp", "999"); put("payment_methods", JsonArray(listOf(JsonPrimitive("cash"))))
        put("staff_name", JsonNull); put("device_name", JsonNull); put("currency_code", "CLP"); put("time_zone", "America/Santiago"); put("latest_update_at", TIME)
    }
    private fun cursor(id: String = SALE) = buildJsonObject { put("before_occurred_at", TIME); put("before_sale_id", id) }
    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))
    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected a fail-closed contract error") } catch (expected: IllegalArgumentException) { }
    }
    companion object {
        private const val OWNER = "10000000-0000-4000-8000-000000000001"
        private const val SHOP = "20000000-0000-4000-8000-000000000001"
        private const val DEVICE = "30000000-0000-4000-8000-000000000001"
        private const val SALE = "40000000-0000-4000-8000-000000000001"
        private const val OTHER = "f0000000-0000-4000-8000-000000000001"
        private const val TIME = "2026-11-05T12:34:56.123456+00:00"
    }
}
