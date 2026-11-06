package com.example.merchandisecontrolsplitview.pos

import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object PosReadCodec {
    const val MAXIMUM_RESPONSE_BYTES = 5 * 1024 * 1024
    private val json = Json { isLenient = false; allowSpecialFloatingPointValues = false }
    private val denials = setOf("unauthenticated", "invalid_request", "permission_denied", "device_not_authorized", "metadata_filter_not_allowed", "currency_unsupported", "not_found")

    fun request(request: PosReadRequest): String = buildJsonObject {
        put("p_shop_id", request.scope.shopId)
        put("p_device_identifier", request.scope.deviceIdentifier)
        put("p_action", request.action.wire)
        putNullable("p_from_date", request.period?.fromDate)
        putNullable("p_to_date", request.period?.toDate)
        put("p_limit", request.limit)
        putNullable("p_before_occurred_at", request.cursor?.occurredAt?.raw)
        putNullable("p_before_sale_id", request.cursor?.saleId)
        putNullable("p_status", request.filters.status?.wire)
        putNullable("p_business_kind", request.filters.businessKind?.wire)
        putNullable("p_payment_method", request.filters.paymentMethod)
        putNullable("p_staff_id", request.filters.staffId)
        putNullable("p_device_id", request.filters.deviceId)
        putNullable("p_sale_number", request.filters.saleNumber)
        putNullable("p_pos_sale_id", request.saleId)
    }.toString()

    /** This is the only decoder entry. It parses no financial data before the complete authority envelope. */
    fun response(bytes: ByteArray, request: PosReadRequest): PosEnvelope {
        checkContract(bytes.size in 1..MAXIMUM_RESPONSE_BYTES, "pos_response_size")
        val root = try { json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).obj() }
        catch (failure: IllegalArgumentException) { throw PosContractException("pos_json_invalid") }
        checkContract(root.integer("contract_version") == 1, "pos_contract_version")
        val ok = root.boolean("ok")
        val serverTime = PosInstant.parse(root.string("server_time", 40))
        val code = root.string("code", 64)
        if (!ok) {
            checkContract(code in denials && root["data"] === JsonNull, "pos_denial_invalid")
            return PosEnvelope.Denied(code, serverTime)
        }
        checkContract(code == "ok", "pos_success_code")
        val scope = root.objectValue("scope")
        checkContract(scope.string("principal_kind", 32) == "personal_account", "pos_principal_incompatible")
        val serverScope = PosServerScope(scope.uuid("principal_id"), scope.uuid("shop_id"), scope.uuid("shop_device_id"))
        checkContract(serverScope.principalId == request.scope.principalId && serverScope.shopId == request.scope.shopId &&
            (request.scope.authorizedShopDeviceId == null || serverScope.shopDeviceId == request.scope.authorizedShopDeviceId), "pos_scope_mismatch")
        val capability = root.objectValue("capability")
        checkContract(capability.boolean("sales_read") && capability.string("access_policy", 32) == "online_only", "pos_capability_invalid")
        val metadata = capability.boolean("operational_metadata_read")
        if (!metadata) checkContract(request.filters.staffId == null && request.filters.deviceId == null, "pos_metadata_filter_denied")
        val currency = root.string("currency_code", 3)
        checkContract(currency == "CLP", "pos_currency_unsupported")
        val zone = root.string("time_zone", 128)
        try { ZoneId.of(zone) } catch (failure: RuntimeException) { throw PosContractException("pos_time_zone_invalid") }
        val p = root.objectValue("period")
        val from = p.nullableString("from_date", 10)
        val to = p.nullableString("to_date", 10)
        val period = if (request.action == PosReadAction.DETAIL) {
            checkContract(from == null && to == null, "pos_detail_period_invalid"); null
        } else {
            checkContract(from != null && to != null, "pos_period_missing")
            PosPeriod(requireNotNull(from), requireNotNull(to)).also {
                checkContract(request.period == null || it == request.period, "pos_period_mismatch")
                if (request.period == null) checkContract(it.fromDate == it.toDate, "pos_today_period_invalid")
            }
        }
        val data = root.objectValue("data")
        val decoded = when (request.action) {
            PosReadAction.OVERVIEW -> {
                val days = data.array("summary_days", 366).map { summary(it.obj(), serverScope, requireNotNull(period), currency, zone) }
                val range = requireNotNull(period)
                checkContract(days.map { it.businessDate }.toSet().size == days.size &&
                    days.size.toLong() == ChronoUnit.DAYS.between(PosWire.date(range.fromDate), PosWire.date(range.toDate)) + 1, "pos_summary_days_invalid")
                val page = page(data, request, range, currency, zone, metadata)
                PosReadData.Overview(days, page.first, page.second)
            }
            PosReadAction.PAGE -> page(data, request, requireNotNull(period), currency, zone, metadata).let { PosReadData.Page(it.first, it.second) }
            PosReadAction.DETAIL -> {
                val sale = sale(data.objectValue("sale"), currency, zone, metadata, detail = true)
                checkContract(sale.id == request.saleId, "pos_detail_id_mismatch")
                val lines = data.array("lines", 10_000).map { line(it.obj()) }
                checkContract(lines.map { it.position }.toSet().size == lines.size, "pos_line_position_duplicate")
                PosReadData.Detail(sale, lines)
            }
            PosReadAction.FILTER -> {
                checkContract(data.boolean("can_filter_operational_metadata") == metadata, "pos_metadata_capability_mismatch")
                val staff = data.array("staff", 100).map { named(it.obj()) }
                val devices = data.array("devices", 100).map { named(it.obj()) }
                checkContract(metadata || (staff.isEmpty() && devices.isEmpty()), "pos_metadata_exposed")
                PosReadData.Filter(data.strings("payment_methods", 40, 40), staff, devices)
            }
        }
        return PosEnvelope.Authorized(serverScope, PosCapability(true, metadata), request.action, period, currency, zone, serverTime, decoded, bytes.size)
    }

    private fun page(data: JsonObject, request: PosReadRequest, period: PosPeriod, currency: String, zone: String, metadata: Boolean): Pair<List<PosSale>, PosCursor?> {
        val sales = data.array("sales", request.limit).map { sale(it.obj(), currency, zone, metadata, detail = false) }
        checkContract(sales.map { it.id }.toSet().size == sales.size, "pos_sale_duplicate")
        sales.forEach { checkContract(it.businessDate >= period.fromDate && it.businessDate <= period.toDate, "pos_sale_outside_period") }
        sales.zipWithNext().forEach { (newer, older) -> checkContract(before(older, PosCursor(newer.occurredAt, newer.id)), "pos_page_order_invalid") }
        request.cursor?.let { cursor -> checkContract(sales.all { before(it, cursor) }, "pos_cursor_not_advanced") }
        val cursorValue = data["next_cursor"] ?: throw PosContractException("pos_cursor_missing")
        val cursor = if (cursorValue === JsonNull) null else cursorValue.obj().let { PosCursor(PosInstant.parse(it.string("before_occurred_at", 40)), it.uuid("before_sale_id")) }
        checkContract(if (sales.isEmpty()) cursor == null else cursor == PosCursor(sales.last().occurredAt, sales.last().id), "pos_cursor_mismatch")
        return sales to cursor
    }

    private fun before(sale: PosSale, cursor: PosCursor): Boolean = sale.occurredAt.instant < cursor.occurredAt.instant ||
        (sale.occurredAt.instant == cursor.occurredAt.instant && sale.id < cursor.saleId)

    private fun summary(row: JsonObject, scope: PosServerScope, period: PosPeriod, currency: String, zone: String): PosSummaryDay {
        checkContract(row.uuid("shop_id") == scope.shopId && row.string("currency_code", 3) == currency && row.string("time_zone", 128) == zone, "pos_summary_scope_mismatch")
        val day = row.date("business_date")
        checkContract(day >= period.fromDate && day <= period.toDate, "pos_summary_outside_period")
        PosInstant.parse(row.string("server_time", 40))
        return PosSummaryDay(day, row.money("gross_sales_clp"), row.money("discounts_clp"), row.money("refunds_clp"), row.money("net_revenue_clp"),
            row.integer("sale_count"), row.integer("refund_count"), row.integer("void_count"), row.integer("transaction_count"), row.nullableInstant("latest_ledger_at"))
    }

    private fun sale(row: JsonObject, currency: String, zone: String, metadata: Boolean, detail: Boolean): PosSale {
        checkContract(row.string("currency_code", 3) == currency, "pos_sale_currency_mismatch")
        if (!detail) checkContract(row.string("time_zone", 128) == zone, "pos_sale_zone_mismatch")
        val staff = row.nullableString("staff_name", 1024)
        val device = row.nullableString("device_name", 1024)
        checkContract(metadata || (staff == null && device == null), "pos_metadata_exposed")
        val kind = PosBusinessKind.entries.singleOrNull { it.wire == row.string("business_kind", 16) } ?: throw PosContractException("pos_kind_invalid")
        val status = PosSaleStatus.entries.singleOrNull { it.wire == row.string("sale_status", 16) } ?: throw PosContractException("pos_sale_status_invalid")
        return PosSale(row.uuid("pos_sale_id"), row.string("sale_number", 1024), PosInstant.parse(row.string("occurred_at", 40)), row.date("business_date"),
            kind, status, row.nullableString("fiscal_status", 128), row.money("gross_amount_clp"), row.money("discount_amount_clp"), row.money("net_amount_clp"),
            row.strings("payment_methods", 40, 40), staff, device, PosInstant.parse(row.string(if (detail) "updated_at" else "latest_update_at", 40)))
    }

    private fun line(row: JsonObject): PosSaleLine {
        val position = row.integer("line_position")
        val type = row.string("line_type", 64)
        checkContract(position >= 1 && type in setOf("item", "discount", "tax", "adjustment"), "pos_line_invalid")
        return PosSaleLine(position, type, row.nullableString("product_id", 36)?.let(PosWire::uuid),
            row.nullableString("item_number", 1024), row.nullableString("barcode", 1024), row.nullableString("product_name", 4096),
            PosWire.quantity(row.string("quantity", 1024)), row.money("unit_amount_clp"), row.money("line_amount_clp"))
    }
    private fun named(row: JsonObject) = PosNamedOption(row.uuid("id"), row.string("name", 1024))
    private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: throw PosContractException("pos_object_invalid")
    private fun JsonObject.objectValue(key: String) = (get(key) ?: throw PosContractException("pos_field_missing")).obj()
    private fun JsonObject.array(key: String, maximum: Int): JsonArray = (get(key) as? JsonArray)?.also { checkContract(it.size <= maximum, "pos_array_limit") } ?: throw PosContractException("pos_array_invalid")
    private fun JsonObject.strings(key: String, maximum: Int, itemMaximum: Int): List<String> =
        array(key, maximum).map { it.boundedString(itemMaximum) }

    private fun JsonObject.string(key: String, maximum: Int): String = get(key).boundedString(maximum)

    private fun JsonElement?.boundedString(maximum: Int): String {
        val value = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw PosContractException("pos_string_invalid")
        checkContract(value.length <= maximum, "pos_string_limit")
        return value
    }
    private fun JsonObject.nullableString(key: String, maximum: Int): String? = when (get(key) ?: throw PosContractException("pos_field_missing")) { JsonNull -> null; else -> string(key, maximum) }
    private fun JsonObject.boolean(key: String): Boolean = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.let { when (it) { "true" -> true; "false" -> false; else -> null } } ?: throw PosContractException("pos_boolean_invalid")
    private fun JsonObject.integer(key: String): Int = (get(key) as? JsonPrimitive)?.takeIf { !it.isString && Regex("(?:0|[1-9][0-9]*)").matches(it.content) }?.content?.toIntOrNull() ?: throw PosContractException("pos_integer_invalid")
    private fun JsonObject.money(key: String): Long = PosWire.signedLong(string(key, 21))
    private fun JsonObject.uuid(key: String): String = PosWire.uuid(string(key, 36))
    private fun JsonObject.date(key: String): String = string(key, 10).also { PosWire.date(it) }
    private fun JsonObject.nullableInstant(key: String): PosInstant? = nullableString(key, 40)?.let(PosInstant::parse)
    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: String?) { put(key, value?.let(::JsonPrimitive) ?: JsonNull) }
    private fun checkContract(condition: Boolean, code: String) { if (!condition) throw PosContractException(code) }
}
