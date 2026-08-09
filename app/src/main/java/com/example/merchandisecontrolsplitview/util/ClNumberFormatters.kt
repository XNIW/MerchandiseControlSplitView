package com.example.merchandisecontrolsplitview.util

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToLong

private enum class NumericDisplayKind {
    PRICE,
    QUANTITY,
    PERCENT,
    IDENTITY,
}

internal enum class OptionalNumericInputStatus {
    EMPTY,
    VALID,
    INVALID,
    NEGATIVE,
}

internal data class OptionalNumericInputValidation(
    val status: OptionalNumericInputStatus,
    val value: Double? = null,
) {
    val isAccepted: Boolean
        get() = status == OptionalNumericInputStatus.EMPTY ||
            status == OptionalNumericInputStatus.VALID
}

private val groupedIntegerPattern = Regex("^[+-]?\\d{1,3}([.,]\\d{3})+$")
private val simpleDecimalPattern = Regex("^[+-]?(\\d+([.,]\\d*)?|[.,]\\d+)$")
private val scientificDecimalPattern =
    Regex("^[+-]?(\\d+([.,]\\d*)?|[.,]\\d+)[eE][+-]?\\d+$")
private val clGroupedDecimalPattern = Regex("^[+-]?\\d{1,3}(\\.\\d{3})+,\\d+$")
private val dotGroupedDecimalPattern = Regex("^[+-]?\\d{1,3}(,\\d{3})+\\.\\d+$")

private fun normalizedColumnKey(columnKey: String?): String? =
    columnKey?.trim()?.lowercase(Locale.ROOT)

private fun clSymbols(): DecimalFormatSymbols =
    DecimalFormatSymbols(Locale.ROOT).apply {
        groupingSeparator = '.'
        decimalSeparator = ','
    }

private fun clFormatter(
    pattern: String,
    useGrouping: Boolean = true,
): DecimalFormat = DecimalFormat(pattern, clSymbols()).apply {
    isGroupingUsed = useGrouping
    roundingMode = RoundingMode.HALF_UP
}

private fun formatClDecimal(
    number: Double?,
    maxDecimals: Int,
    useGrouping: Boolean,
    fallback: String,
): String {
    if (number == null) return fallback
    val fractionPattern = if (maxDecimals > 0) ".${"#".repeat(maxDecimals)}" else ""
    val basePattern = if (useGrouping) "#,##0" else "0"
    return clFormatter("$basePattern$fractionPattern", useGrouping = useGrouping).format(number)
}

private fun parseNumericInput(
    value: String?,
    allowGroupedIntegerPattern: Boolean,
): Double? {
    val clean = value
        ?.trim()
        ?.replace(" ", "")
        .orEmpty()

    if (clean.isBlank()) return null

    val normalized = when {
        allowGroupedIntegerPattern && groupedIntegerPattern.matches(clean) ->
            clean.replace(".", "").replace(",", "")
        clGroupedDecimalPattern.matches(clean) ->
            clean.replace(".", "").replace(",", ".")
        dotGroupedDecimalPattern.matches(clean) ->
            clean.replace(",", "")
        scientificDecimalPattern.matches(clean) -> clean.replace(",", ".")
        simpleDecimalPattern.matches(clean) -> clean.replace(",", ".")
        else -> return null
    }

    return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
}

private fun validateOptionalNumericInput(
    value: String?,
    parser: (String?) -> Double?,
): OptionalNumericInputValidation {
    if (value.isNullOrBlank()) {
        return OptionalNumericInputValidation(OptionalNumericInputStatus.EMPTY)
    }

    val parsed = parser(value)
        ?: return OptionalNumericInputValidation(OptionalNumericInputStatus.INVALID)
    if (parsed < 0.0) {
        return OptionalNumericInputValidation(
            status = OptionalNumericInputStatus.NEGATIVE,
            value = parsed,
        )
    }
    return OptionalNumericInputValidation(
        status = OptionalNumericInputStatus.VALID,
        value = parsed,
    )
}

private fun numericDisplayKindForColumn(columnKey: String?): NumericDisplayKind? =
    when (normalizedColumnKey(columnKey)) {
        "purchaseprice",
        "retailprice",
        "totalprice",
        "discountedprice",
        "oldpurchaseprice",
        "oldretailprice" -> NumericDisplayKind.PRICE
        "quantity",
        "realquantity",
        "stockquantity" -> NumericDisplayKind.QUANTITY
        "discount" -> NumericDisplayKind.PERCENT
        "barcode",
        "itemnumber",
        "rownumber" -> NumericDisplayKind.IDENTITY
        else -> null
    }

fun formatClPricePlainDisplay(number: Double?): String =
    formatClDecimal(number, maxDecimals = 0, useGrouping = true, fallback = "-")

fun formatClSummaryMoney(number: Double?): String =
    number?.let { "\$ ${formatClPricePlainDisplay(it)}" } ?: "-"

fun formatClQuantityDisplayReadOnly(number: Double?): String =
    formatClDecimal(number, maxDecimals = 3, useGrouping = true, fallback = "-")

fun formatClCount(number: Int): String =
    clFormatter("#,##0").format(number)

fun formatClPercentDisplay(number: Double?): String =
    number?.let {
        "${formatClDecimal(it, maxDecimals = 2, useGrouping = false, fallback = "-")}%"
    } ?: "-"

fun formatClPriceInput(number: Double?): String =
    number?.roundToLong()?.toString().orEmpty()

fun formatClQuantityInput(number: Double?): String =
    formatClDecimal(number, maxDecimals = 3, useGrouping = false, fallback = "")

fun parseUserNumericInput(value: String?): Double? =
    parseNumericInput(value, allowGroupedIntegerPattern = false)

fun parseUserPriceInput(value: String?): Double? =
    parseNumericInput(value, allowGroupedIntegerPattern = true)

fun parseUserQuantityInput(value: String?): Double? =
    parseNumericInput(value, allowGroupedIntegerPattern = true)

internal fun validateOptionalUserPriceInput(value: String?): OptionalNumericInputValidation =
    validateOptionalNumericInput(value, ::parseUserPriceInput)

internal fun validateOptionalUserQuantityInput(value: String?): OptionalNumericInputValidation =
    validateOptionalNumericInput(value, ::parseUserQuantityInput)

fun normalizeClPriceInput(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return ""
    return parseUserPriceInput(trimmed)?.roundToLong()?.toString() ?: trimmed
}

fun normalizeClQuantityInput(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return ""
    return parseUserQuantityInput(trimmed)?.let(::formatClQuantityInput) ?: trimmed
}

fun formatGridNumericDisplay(
    rawValue: String,
    columnKey: String?,
): String {
    if (rawValue.isBlank()) return rawValue

    return when (numericDisplayKindForColumn(columnKey)) {
        NumericDisplayKind.PRICE ->
            parseUserPriceInput(rawValue)?.let(::formatClPricePlainDisplay) ?: rawValue
        NumericDisplayKind.QUANTITY ->
            parseUserQuantityInput(rawValue)?.let(::formatClQuantityDisplayReadOnly) ?: rawValue
        NumericDisplayKind.PERCENT ->
            parseUserNumericInput(rawValue)?.let(::formatClPercentDisplay) ?: rawValue
        NumericDisplayKind.IDENTITY,
        null -> rawValue
    }
}
