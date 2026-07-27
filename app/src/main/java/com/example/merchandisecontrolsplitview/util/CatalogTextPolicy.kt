package com.example.merchandisecontrolsplitview.util

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer

/**
 * Pure domain policy shared by every Android catalog write boundary.
 *
 * Lengths are measured in UTF-16 code units after NFC, matching the common
 * `catalog_text_policy_v1` contract. Rejections never retain or expose the raw
 * value so callers can report them without leaking invisible text.
 */
object CatalogTextPolicy {
    const val VERSION = "catalog_text_policy_v1"

    object Limits {
        const val PRODUCT_NAME = 240
        const val SECOND_PRODUCT_NAME = 240
        const val SUPPLIER_NAME = 160
        const val CATEGORY_NAME = 160
        const val BARCODE = 96
        const val ITEM_NUMBER = 120
        const val REMOTE_ID = 256
    }

    enum class Change(val contractValue: String) {
        LINE_BREAK_TO_SPACE("line_break_to_space"),
        TAB_TO_SPACE("tab_to_space"),
        SPACE_SEPARATOR_TO_SPACE("space_separator_to_space"),
        SPACE_COLLAPSED("space_collapsed"),
        TRIMMED("trimmed"),
        UNICODE_NFC("unicode_nfc")
    }

    enum class RejectionReason(val contractValue: String) {
        EMPTY_REQUIRED("empty_required"),
        PROHIBITED_CONTROL("prohibited_control"),
        PROHIBITED_LINE_SEPARATOR("prohibited_line_separator"),
        PROHIBITED_ZERO_WIDTH("prohibited_zero_width"),
        PROHIBITED_BOM("prohibited_bom"),
        PROHIBITED_BIDI("prohibited_bidi"),
        INVALID_UTF16("invalid_utf16"),
        INVALID_UTF8("invalid_utf8"),
        TOO_LONG("too_long"),
        IDENTITY_COLLISION_AFTER_TRIM("identity_collision_after_trim")
    }

    sealed interface Outcome {
        data class Unchanged(val value: String) : Outcome
        data class Normalized(
            val value: String,
            val changes: Set<Change>
        ) : Outcome

        data class Rejected(val reason: RejectionReason) : Outcome
    }

    data class FieldRejection(
        val field: CatalogTextField,
        val reason: RejectionReason
    )

    fun display(
        raw: String,
        required: Boolean,
        maxLength: Int
    ): Outcome {
        validateUtf16(raw)?.let { return Outcome.Rejected(it) }
        validateDisplayCodePoints(raw)?.let { return Outcome.Rejected(it) }

        var sawLineBreak = false
        var sawTab = false
        var sawSpaceSeparator = false
        val spaced = buildString(raw.length) {
            var index = 0
            while (index < raw.length) {
                val codePoint = raw.codePointAt(index)
                when {
                    codePoint == '\r'.code -> {
                        sawLineBreak = true
                        append(' ')
                        if (index + 1 < raw.length && raw[index + 1] == '\n') {
                            index += 1
                        }
                    }
                    codePoint == '\n'.code -> {
                        sawLineBreak = true
                        append(' ')
                    }
                    codePoint == '\t'.code -> {
                        sawTab = true
                        append(' ')
                    }
                    Character.getType(codePoint) == Character.SPACE_SEPARATOR.toInt() -> {
                        if (codePoint != ' '.code) sawSpaceSeparator = true
                        append(' ')
                    }
                    else -> appendCodePoint(codePoint)
                }
                index += Character.charCount(codePoint)
            }
        }

        val collapsed = collapseAsciiSpaces(spaced)
        val wasCollapsed = collapsed != spaced && (
            sawLineBreak ||
                sawTab ||
                sawSpaceSeparator ||
                INTERNAL_REPEATED_SPACES.containsMatchIn(spaced)
            )
        val trimmed = collapsed.trim(' ')
        val wasTrimmed = trimmed != collapsed
        val nfc = Normalizer.normalize(trimmed, Normalizer.Form.NFC)
        val wasNfcNormalized = nfc != trimmed

        if (nfc.length > maxLength) return Outcome.Rejected(RejectionReason.TOO_LONG)
        if (required && nfc.isEmpty()) return Outcome.Rejected(RejectionReason.EMPTY_REQUIRED)

        val changes = linkedSetOf<Change>()
        if (sawLineBreak) changes += Change.LINE_BREAK_TO_SPACE
        if (sawTab) changes += Change.TAB_TO_SPACE
        if (sawSpaceSeparator) changes += Change.SPACE_SEPARATOR_TO_SPACE
        if (wasCollapsed) changes += Change.SPACE_COLLAPSED
        if (wasTrimmed) changes += Change.TRIMMED
        if (wasNfcNormalized) changes += Change.UNICODE_NFC

        return if (changes.isEmpty()) {
            Outcome.Unchanged(nfc)
        } else {
            Outcome.Normalized(nfc, changes)
        }
    }

    fun strict(
        raw: String,
        required: Boolean,
        maxLength: Int
    ): Outcome {
        validateUtf16(raw)?.let { return Outcome.Rejected(it) }
        validateStrictCodePoints(raw)?.let { return Outcome.Rejected(it) }

        val trimmed = raw.trim()
        if (trimmed.length > maxLength) return Outcome.Rejected(RejectionReason.TOO_LONG)
        if (required && trimmed.isEmpty()) return Outcome.Rejected(RejectionReason.EMPTY_REQUIRED)
        return if (trimmed == raw) {
            Outcome.Unchanged(trimmed)
        } else {
            Outcome.Normalized(trimmed, setOf(Change.TRIMMED))
        }
    }

    fun displayUtf8(
        bytes: ByteArray,
        required: Boolean,
        maxLength: Int
    ): Outcome {
        val decoded = decodeUtf8(bytes) ?: return Outcome.Rejected(RejectionReason.INVALID_UTF8)
        return display(decoded, required, maxLength)
    }

    fun strictUtf8(
        bytes: ByteArray,
        required: Boolean,
        maxLength: Int
    ): Outcome {
        val decoded = decodeUtf8(bytes) ?: return Outcome.Rejected(RejectionReason.INVALID_UTF8)
        return strict(decoded, required, maxLength)
    }

    fun validateDistinctStrictIdentities(
        rawValues: List<String>,
        required: Boolean,
        maxLength: Int
    ): Outcome.Rejected? {
        val firstRawByCanonical = mutableMapOf<String, String>()
        rawValues.forEach { raw ->
            val canonical = when (val outcome = strict(raw, required, maxLength)) {
                is Outcome.Rejected -> return outcome
                is Outcome.Normalized -> outcome.value
                is Outcome.Unchanged -> outcome.value
            }
            if (!required && canonical.isEmpty()) return@forEach
            val firstRaw = firstRawByCanonical.putIfAbsent(canonical, raw)
            if (firstRaw != null && firstRaw != raw) {
                return Outcome.Rejected(RejectionReason.IDENTITY_COLLISION_AFTER_TRIM)
            }
        }
        return null
    }

    fun valueOrNull(outcome: Outcome): String? = when (outcome) {
        is Outcome.Normalized -> outcome.value
        is Outcome.Unchanged -> outcome.value
        is Outcome.Rejected -> null
    }

    private fun validateUtf16(value: String): RejectionReason? {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char.isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) {
                        return RejectionReason.INVALID_UTF16
                    }
                    index += 2
                }
                char.isLowSurrogate() -> return RejectionReason.INVALID_UTF16
                else -> index += 1
            }
        }
        return null
    }

    private fun validateDisplayCodePoints(value: String): RejectionReason? =
        validateCodePoints(value, allowDisplayWhitespace = true, allowJoinControls = true)

    private fun validateStrictCodePoints(value: String): RejectionReason? =
        validateCodePoints(value, allowDisplayWhitespace = false, allowJoinControls = false)

    private fun validateCodePoints(
        value: String,
        allowDisplayWhitespace: Boolean,
        allowJoinControls: Boolean
    ): RejectionReason? {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            when {
                codePoint == 0x2028 || codePoint == 0x2029 ->
                    return RejectionReason.PROHIBITED_LINE_SEPARATOR
                codePoint in 0x202A..0x202E || codePoint in 0x2066..0x2069 ->
                    return RejectionReason.PROHIBITED_BIDI
                codePoint == 0xFEFF ->
                    return RejectionReason.PROHIBITED_BOM
                codePoint == 0x200B || codePoint == 0x2060 ||
                    (!allowJoinControls && (codePoint == 0x200C || codePoint == 0x200D)) ->
                    return RejectionReason.PROHIBITED_ZERO_WIDTH
                isC0OrC1Control(codePoint) &&
                    !(allowDisplayWhitespace && (
                        codePoint == '\r'.code ||
                            codePoint == '\n'.code ||
                            codePoint == '\t'.code
                        )) ->
                    return RejectionReason.PROHIBITED_CONTROL
            }
            index += Character.charCount(codePoint)
        }
        return null
    }

    private fun isC0OrC1Control(codePoint: Int): Boolean =
        codePoint in 0x0000..0x001F || codePoint in 0x007F..0x009F

    private fun collapseAsciiSpaces(value: String): String = buildString(value.length) {
        var previousWasSpace = false
        value.forEach { char ->
            if (char == ' ') {
                if (!previousWasSpace) append(char)
                previousWasSpace = true
            } else {
                append(char)
                previousWasSpace = false
            }
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private val INTERNAL_REPEATED_SPACES = Regex("\\S {2,}\\S")
}

enum class CatalogTextField {
    BARCODE,
    ITEM_NUMBER,
    PRODUCT_NAME,
    SECOND_PRODUCT_NAME,
    SUPPLIER_NAME,
    CATEGORY_NAME,
    REMOTE_ID
}

class CatalogTextValidationException(
    val rejection: CatalogTextPolicy.FieldRejection
) : IllegalArgumentException(
    "catalog_text_rejected:${rejection.field.name.lowercase()}:${rejection.reason.contractValue}"
)
