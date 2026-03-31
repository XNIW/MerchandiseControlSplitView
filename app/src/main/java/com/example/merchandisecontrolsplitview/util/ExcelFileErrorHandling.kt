package com.example.merchandisecontrolsplitview.util

import android.content.Context
import com.example.merchandisecontrolsplitview.R
import org.apache.poi.ooxml.POIXMLException
import org.apache.poi.util.RecordFormatException
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.io.IOException

internal sealed interface ExcelFileUserError {
    data object DifferentColumns : ExcelFileUserError
    data object IncompatibleFileStructure : ExcelFileUserError
    data object MainFileNeeded : ExcelFileUserError
    data object FirstFileEmptyOrInvalid : ExcelFileUserError
    data object EmptyOrInvalid : ExcelFileUserError
    data object FileAccessDenied : ExcelFileUserError
    data object FileReadFailed : ExcelFileUserError
    data object FileTooLargeOrComplex : ExcelFileUserError
    data object LegacyXlsUnreadable : ExcelFileUserError
    data object StrictOoXmlNotSupported : ExcelFileUserError
    data object Unknown : ExcelFileUserError
}

internal class ExcelInputStreamUnavailableException :
    IOException("Excel input stream unavailable")

internal fun classifyExcelFileUserError(
    context: Context,
    throwable: Throwable
): ExcelFileUserError {
    return when {
        matchesKnownMessage(context, throwable, R.string.error_different_columns) ->
            ExcelFileUserError.DifferentColumns
        matchesKnownMessage(context, throwable, R.string.error_incompatible_file_structure) ->
            ExcelFileUserError.IncompatibleFileStructure
        matchesKnownMessage(context, throwable, R.string.error_main_file_needed) ->
            ExcelFileUserError.MainFileNeeded
        matchesKnownMessage(context, throwable, R.string.error_first_file_empty_or_invalid) ->
            ExcelFileUserError.FirstFileEmptyOrInvalid
        matchesKnownMessage(context, throwable, R.string.error_file_empty_or_invalid) ->
            ExcelFileUserError.EmptyOrInvalid
        throwable is OutOfMemoryError ->
            ExcelFileUserError.FileTooLargeOrComplex
        isLegacyXlsObjectRecordFailure(throwable) ->
            ExcelFileUserError.LegacyXlsUnreadable
        isStrictOoXmlFailure(throwable) ->
            ExcelFileUserError.StrictOoXmlNotSupported
        throwable is ExcelInputStreamUnavailableException ->
            ExcelFileUserError.FileAccessDenied
        throwable is SecurityException ->
            ExcelFileUserError.FileAccessDenied
        throwable is IOException ->
            ExcelFileUserError.FileReadFailed
        else -> ExcelFileUserError.Unknown
    }
}

internal fun resolveExcelFileErrorMessage(
    context: Context,
    throwable: Throwable,
    unknownFallbackResId: Int
): String {
    return when (classifyExcelFileUserError(context, throwable)) {
        ExcelFileUserError.DifferentColumns ->
            context.getString(R.string.error_different_columns)
        ExcelFileUserError.IncompatibleFileStructure ->
            context.getString(R.string.error_incompatible_file_structure)
        ExcelFileUserError.MainFileNeeded ->
            context.getString(R.string.error_main_file_needed)
        ExcelFileUserError.FirstFileEmptyOrInvalid ->
            context.getString(R.string.error_first_file_empty_or_invalid)
        ExcelFileUserError.EmptyOrInvalid ->
            context.getString(R.string.error_file_empty_or_invalid)
        ExcelFileUserError.FileAccessDenied ->
            context.getString(R.string.error_file_access_denied)
        ExcelFileUserError.FileReadFailed ->
            context.getString(R.string.error_file_read_failed)
        ExcelFileUserError.FileTooLargeOrComplex ->
            context.getString(R.string.error_file_too_large_or_complex)
        ExcelFileUserError.LegacyXlsUnreadable ->
            context.getString(R.string.error_legacy_xls_unreadable)
        ExcelFileUserError.StrictOoXmlNotSupported ->
            context.getString(R.string.error_strict_ooxml_not_supported)
        ExcelFileUserError.Unknown ->
            context.getString(unknownFallbackResId)
    }
}

private fun matchesKnownMessage(
    context: Context,
    throwable: Throwable,
    resId: Int
): Boolean {
    return throwable.message?.trim() == context.getString(resId)
}

internal fun isLegacyXlsObjectRecordFailure(throwable: Throwable): Boolean {
    return throwable.selfAndCauses().any { cause ->
        cause is RecordFormatException &&
            cause.message?.contains("Unexpected size (0)", ignoreCase = true) == true &&
            cause.stackTrace.any { frame ->
                frame.className.startsWith("org.apache.poi.hssf.record.")
            }
    }
}

internal fun isStrictOoXmlFailure(throwable: Throwable): Boolean {
    return throwable.selfAndCauses().any { cause ->
        cause is POIXMLException &&
            cause.message?.contains("Strict OOXML", ignoreCase = true) == true
    }
}

private fun Throwable.selfAndCauses(): Sequence<Throwable> = sequence {
    val queue = ArrayDeque<Throwable>()
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())

    queue.add(this@selfAndCauses)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (!visited.add(current)) {
            continue
        }

        yield(current)
        current.cause?.let(queue::addLast)
        current.suppressed.forEach(queue::addLast)
    }
}
