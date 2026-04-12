package com.example.merchandisecontrolsplitview.ui.screens

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val historyTimestampParser: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

private val historyTechnicalIdPrefixPatterns = listOf(
    Regex("""^\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}-\d{3}_"""),
    Regex("""^\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}_"""),
    Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}_"""),
    Regex("""^\d{4}-\d{2}-\d{2}_\d{2}:\d{2}:\d{2}_""")
)
private val historySpreadsheetExtensions = listOf(".xlsx", ".xlsm", ".xls")
private val historyUnderscorePattern = Regex("""_+""")
private val historyWhitespacePattern = Regex("""\s+""")
private val historyComparisonSeparatorPattern = Regex("""[_\-\s]+""")

internal fun formatHistoryTimestamp(timestamp: String, locale: Locale = Locale.getDefault()): String {
    val outputFormatter = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(locale)

    return parseHistoryEntryTimestamp(timestamp)
        ?.format(outputFormatter)
        ?: timestamp
}

internal fun formatHistoryEntryContextTimestamp(
    timestamp: String,
    locale: Locale = Locale.getDefault()
): String {
    val outputFormatter = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)

    return parseHistoryEntryTimestamp(timestamp)
        ?.format(outputFormatter)
        ?: timestamp
}

internal fun formatHistoryMonthLabel(timestamp: String, locale: Locale = Locale.getDefault()): String {
    val formatter = DateTimeFormatter.ofPattern("LLLL yyyy", locale)
    val formatted = parseHistoryEntryTimestamp(timestamp)
        ?.format(formatter)
        ?: timestamp.takeIf { it.length >= 7 }?.substring(0, 7).orEmpty()

    return formatted.replaceFirstChar { firstChar ->
        if (firstChar.isLowerCase()) {
            firstChar.titlecase(locale)
        } else {
            firstChar.toString()
        }
    }
}

internal fun historyMonthKey(timestamp: String): String =
    parseHistoryEntryTimestamp(timestamp)
        ?.let { "%04d-%02d".format(Locale.ROOT, it.year, it.monthValue) }
        ?: timestamp.takeIf { it.length >= 7 }?.substring(0, 7).orEmpty()

internal fun formatHistoryEntryDisplayTitle(id: String): String {
    val trimmedId = id.trim()
    if (trimmedId.isBlank()) return id

    val baseName = removeHistorySpreadsheetExtension(trimmedId)
    val withoutTimestamp = historyTechnicalIdPrefixPatterns.firstNotNullOfOrNull { pattern ->
        pattern.find(baseName)
            ?.takeIf { it.range.first == 0 }
            ?.let { baseName.removeRange(it.range) }
    } ?: return trimmedId

    val withoutTechnicalToken = stripLeadingHistoryTechnicalToken(withoutTimestamp)
    val displayTitle = historyWhitespacePattern
        .replace(historyUnderscorePattern.replace(withoutTechnicalToken, " "), " ")
        .trim()

    return displayTitle.takeIf { it.isNotBlank() } ?: trimmedId
}

internal fun shouldShowTechnicalRow(id: String, displayTitle: String): Boolean {
    val trimmedId = id.trim()
    val trimmedDisplayTitle = displayTitle.trim()

    if (trimmedId.isBlank() || trimmedDisplayTitle.isBlank()) return false
    if (trimmedId == trimmedDisplayTitle) return false
    if (trimmedId.length <= 32) return false

    val comparableId = normalizeHistoryComparisonText(removeHistorySpreadsheetExtension(trimmedId))
    val comparableDisplayTitle = normalizeHistoryComparisonText(trimmedDisplayTitle)

    if (comparableDisplayTitle.isBlank()) return false

    return !comparableId.contains(comparableDisplayTitle)
}

internal fun normalizeHistoryComparisonText(value: String): String =
    historyComparisonSeparatorPattern
        .replace(removeHistorySpreadsheetExtension(value).lowercase(Locale.ROOT), " ")
        .trim()

private fun parseHistoryEntryTimestamp(timestamp: String): LocalDateTime? =
    runCatching {
        LocalDateTime.parse(timestamp, historyTimestampParser)
    }.getOrNull()

private fun stripLeadingHistoryTechnicalToken(value: String): String {
    val separatorIndex = value.indexOf('_')
    if (separatorIndex <= 0) return value

    val firstSegment = value.substring(0, separatorIndex)
    return if (looksLikeHistoryTechnicalToken(firstSegment)) {
        value.substring(separatorIndex + 1)
    } else {
        value
    }
}

private fun looksLikeHistoryTechnicalToken(segment: String): Boolean {
    if (segment.length < 10) return false
    if (!segment.all { it.isLetterOrDigit() || it == '-' }) return false

    val hasLetter = segment.any { it.isLetter() }
    val hasDigit = segment.any { it.isDigit() }
    val isNumericToken = segment.all { it.isDigit() || it == '-' }

    return hasDigit && (hasLetter || (isNumericToken && segment.length >= 12))
}

private fun removeHistorySpreadsheetExtension(value: String): String =
    historySpreadsheetExtensions.firstOrNull { value.endsWith(it, ignoreCase = true) }
        ?.let { value.dropLast(it.length) }
        ?: value
