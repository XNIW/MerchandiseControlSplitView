package com.example.merchandisecontrolsplitview.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.RowImportError
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.IOException

object ErrorExporter {
    private const val errorReasonColumnKey = "__import_error_reason__"
    private const val tag = "ErrorExporter"

    /**
     * Esporta una lista di errori di importazione in un file XLSX.
     *
     * @param errors La lista di `RowImportError` da esportare.
     * @param context Il Context dell'applicazione.
     * @param fileUri L'URI del file scelto dall'utente tramite `CreateDocument`.
     */
    fun exportErrorsToXlsx(
        errors: List<RowImportError>,
        context: Context,
        fileUri: Uri
    ): Boolean {
        if (errors.isEmpty()) return false

        return try {
            XSSFWorkbook().use { workbook ->
                val sheet = workbook.createSheet(
                    context.getString(R.string.import_error_export_sheet_name)
                )

                val headers = errors.first().rowContent.keys.toList() + errorReasonColumnKey
                val headerRow = sheet.createRow(0)
                headers.forEachIndexed { index, headerKey ->
                    val headerText = if (headerKey == errorReasonColumnKey) {
                        context.getString(R.string.import_error_export_column_reason)
                    } else {
                        getLocalizedHeader(context, headerKey)
                    }
                    headerRow.createCell(index).setCellValue(headerText)
                }

                errors.forEachIndexed { rowIndex, error ->
                    val dataRow = sheet.createRow(rowIndex + 1)
                    val rowDataMap = error.rowContent

                    headers.forEachIndexed { colIndex, headerKey ->
                        if (headerKey != errorReasonColumnKey) {
                            val cellValue = rowDataMap[headerKey] ?: ""
                            dataRow.createCell(colIndex).setCellValue(cellValue)
                        }
                    }

                    val reasonText = context.getString(
                        error.errorReasonResId,
                        *error.formatArgs.toTypedArray()
                    )
                    dataRow.createCell(headers.size - 1).setCellValue(reasonText)
                }

                sheet.defaultColumnWidth = 15
                context.contentResolver.openOutputStream(fileUri)?.use { outputStream ->
                    workbook.write(outputStream)
                } ?: throw IOException("Unable to open output stream for $fileUri")
            }
            true
        } catch (e: IOException) {
            Log.e(tag, "Failed to export import errors", e)
            false
        } catch (e: SecurityException) {
            Log.e(tag, "Missing permission while exporting import errors", e)
            false
        }
    }
}
