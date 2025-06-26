package com.example.merchandisecontrolsplitview.util

import android.content.Context
import android.net.Uri
import com.example.merchandisecontrolsplitview.data.RowImportError
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.IOException

object ErrorExporter {

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
    ) {
        if (errors.isEmpty()) return // Non fare nulla se non ci sono errori

        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Errori di Importazione")

        try {
            // --- 1. Creazione dell'Intestazione (Header) ---
            // Prende le chiavi dalla mappa del primo errore per creare le colonne.
            // Aggiunge la colonna finale "Errore".
            val headers = errors.first().rowContent.keys.toList() + "Errore"
            val headerRow = sheet.createRow(0)
            headers.forEachIndexed { index, headerText ->
                headerRow.createCell(index).setCellValue(getLocalizedHeader(context, headerText))
            }

            // --- 2. Scrittura delle Righe di Errore ---
            errors.forEachIndexed { rowIndex, error ->
                val dataRow = sheet.createRow(rowIndex + 1)
                val rowDataMap = error.rowContent

                // Scrive i dati di ogni colonna in base all'header
                headers.forEachIndexed { colIndex, headerKey ->
                    if (headerKey != "Errore") {
                        val cellValue = rowDataMap[headerKey] ?: ""
                        dataRow.createCell(colIndex).setCellValue(cellValue)
                    }
                }
                // Aggiunge il motivo dell'errore nell'ultima colonna
                dataRow.createCell(headers.size - 1).setCellValue(error.errorReason)
            }

            // --- 3. Adatta la larghezza delle colonne al contenuto ---
            sheet.defaultColumnWidth = 15

            // --- 4. Scrittura del file ---
            context.contentResolver.openOutputStream(fileUri)?.use { outputStream ->
                workbook.write(outputStream)
            }
        } catch (e: IOException) {
            e.printStackTrace()
            // Gestisci l'errore, magari con un Toast
        } finally {
            workbook.close()
        }
    }
}