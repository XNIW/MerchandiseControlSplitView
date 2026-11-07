package com.example.merchandisecontrolsplitview.pos

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Local authority capture. Its epochs are never supplied by or inferred from catalog canWrite. */
internal data class PosScope(
    val principalId: String,
    val shopId: String,
    val deviceIdentifier: String,
    val authGeneration: Long,
    val deviceEpoch: Long,
    val authorizedShopDeviceId: String? = null
)

internal data class PosPeriod(val fromDate: String, val toDate: String) {
    init {
        val from = PosWire.date(fromDate)
        val to = PosWire.date(toDate)
        require(!to.isBefore(from) && ChronoUnit.DAYS.between(from, to) <= 365) { "pos_period_invalid" }
    }
}

/** Keeps the exact server representation, including its microseconds, when sent back as a cursor. */
internal data class PosInstant private constructor(val raw: String, val instant: Instant) {
    init {
        require(raw.length <= 40 && Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?(?:Z|[+-][0-9]{2}:[0-9]{2})").matches(raw)) { "pos_instant_invalid" }
        require(instant == OffsetDateTime.parse(raw).toInstant()) { "pos_instant_mismatch" }
    }
    companion object {
        fun parse(raw: String): PosInstant {
            require(raw.length <= 40 && Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?(?:Z|[+-][0-9]{2}:[0-9]{2})").matches(raw)) { "pos_instant_invalid" }
            return PosInstant(raw, OffsetDateTime.parse(raw).toInstant())
        }
    }
}

internal data class PosCursor(val occurredAt: PosInstant, val saleId: String) {
    init { PosWire.uuid(saleId) }
}

internal enum class PosReadAction(val wire: String) { OVERVIEW("summary"), PAGE("page"), DETAIL("detail"), FILTER("filter") }
internal enum class PosBusinessKind(val wire: String) { SALE("sale"), REFUND("refund"), VOID("void") }
internal enum class PosSaleStatus(val wire: String) { ACCEPTED("accepted"), DUPLICATE("duplicate"), CONFLICT("conflict"), REJECTED("rejected") }

internal data class PosFilters(
    val status: PosSaleStatus? = null,
    val businessKind: PosBusinessKind? = null,
    val paymentMethod: String? = null,
    val staffId: String? = null,
    val deviceId: String? = null,
    val saleNumber: String? = null
)

internal data class PosReadRequest(
    val scope: PosScope,
    val action: PosReadAction,
    val period: PosPeriod? = null,
    val filters: PosFilters = PosFilters(),
    val cursor: PosCursor? = null,
    val limit: Int = 50,
    val saleId: String? = null
) {
    init {
        PosWire.uuid(scope.principalId); PosWire.uuid(scope.shopId)
        scope.authorizedShopDeviceId?.let(PosWire::uuid)
        require(scope.deviceIdentifier.toByteArray(Charsets.UTF_8).size <= 160 && scope.deviceIdentifier.trim().isNotEmpty()) { "pos_device_invalid" }
        require(scope.authGeneration >= 0 && scope.deviceEpoch >= 0 && limit in 1..100) { "pos_request_invalid" }
        filters.paymentMethod?.let { require(it.length in 1..40) }
        filters.saleNumber?.let { require(it.length in 1..80) }
        filters.staffId?.let(PosWire::uuid); filters.deviceId?.let(PosWire::uuid)
        saleId?.let(PosWire::uuid)
        when (action) {
            PosReadAction.OVERVIEW -> require(cursor == null && saleId == null)
            PosReadAction.PAGE -> require(period != null && saleId == null)
            PosReadAction.DETAIL -> require(saleId != null && period == null && cursor == null && filters == PosFilters() && limit == 50)
            PosReadAction.FILTER -> require(period != null && saleId == null && cursor == null && filters == PosFilters() && limit == 50)
        }
    }
}

internal data class PosServerScope(val principalId: String, val shopId: String, val shopDeviceId: String, val principalKind: String = "personal_account")
internal data class PosCapability(val salesRead: Boolean, val metadataRead: Boolean, val accessPolicy: String = "online_only") {
    val operationalMetadataRead: Boolean get() = metadataRead
}

internal data class PosSummaryDay(
    val businessDate: String,
    val grossSalesClp: Long,
    val discountsClp: Long,
    val refundsClp: Long,
    val netRevenueClp: Long,
    val saleCount: Int,
    val refundCount: Int,
    val voidCount: Int,
    val transactionCount: Int,
    val latestLedgerAt: PosInstant?
)

internal data class PosSale(
    val posSaleId: String,
    val saleNumber: String,
    val occurredAt: PosInstant,
    val businessDate: String,
    val businessKind: PosBusinessKind,
    val status: PosSaleStatus,
    val fiscalStatus: String?,
    val grossAmountClp: Long,
    val discountAmountClp: Long,
    val netAmountClp: Long,
    val paymentMethods: List<String>,
    val staffName: String?,
    val deviceName: String?,
    val updatedAt: PosInstant
) {
    val id: String get() = posSaleId
    val number: String get() = saleNumber
    val kind: PosBusinessKind get() = businessKind
}

internal data class PosSaleLine(
    val position: Int,
    val type: String,
    val productId: String?,
    val itemNumber: String?,
    val barcode: String?,
    val productName: String?,
    val quantity: BigDecimal,
    val unitAmountClp: Long,
    val lineAmountClp: Long
)
internal data class PosNamedOption(val id: String, val name: String)

internal sealed interface PosReadData {
    data class Overview(val summaryDays: List<PosSummaryDay>, val sales: List<PosSale>, val nextCursor: PosCursor?) : PosReadData
    data class Page(val sales: List<PosSale>, val nextCursor: PosCursor?) : PosReadData
    data class Detail(val sale: PosSale, val lines: List<PosSaleLine>) : PosReadData
    data class Filter(val paymentMethods: List<String>, val staff: List<PosNamedOption>, val devices: List<PosNamedOption>) : PosReadData
}

internal sealed interface PosEnvelope {
    data class Denied(val code: String, val serverTime: PosInstant) : PosEnvelope
    data class Authorized(
        val serverScope: PosServerScope,
        val capability: PosCapability,
        val action: PosReadAction,
        val period: PosPeriod?,
        val currencyCode: String,
        val timeZone: String,
        val serverTime: PosInstant,
        val data: PosReadData,
        val bodyBytes: Int
    ) : PosEnvelope
}

internal class PosContractException(val code: String) : IllegalArgumentException(code)
internal class PosHttpException(val status: Int, val retryAfterSeconds: Long?) : Exception("pos_http_$status")

/** No floating-point intermediate, rounding, normalization or overflow fallback. */
internal object PosWire {
    fun signedLong(raw: String): Long {
        if (!Regex("(?:0|-[1-9][0-9]*|[1-9][0-9]*)").matches(raw)) throw PosContractException("pos_money_invalid")
        return raw.toLongOrNull() ?: throw PosContractException("pos_money_overflow")
    }
    fun quantity(raw: String): BigDecimal {
        if (raw.length > 1024 || !Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?").matches(raw)) throw PosContractException("pos_quantity_invalid")
        return BigDecimal(raw)
    }
    fun uuid(raw: String): String {
        if (!Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(raw) || UUID.fromString(raw).toString() != raw) throw PosContractException("pos_uuid_invalid")
        return raw
    }
    fun date(raw: String): LocalDate {
        if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(raw)) throw PosContractException("pos_date_invalid")
        return LocalDate.parse(raw)
    }
}
