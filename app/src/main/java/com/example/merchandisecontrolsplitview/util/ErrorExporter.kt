package com.example.merchandisecontrolsplitview.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.merchandisecontrolsplitview.data.RowImportError
import java.io.IOException

/**
 * Un oggetto helper per esportare dati in formati file, come CSV.
 */
object ErrorExporter {

    /**
     * Esporta una lista di errori di importazione in un file CSV.
     * Utilizza il Storage Access Framework per creare un file nella directory
     * selezionata dall'utente.
     *
     * @param errors La lista di `RowImportError` da esportare.
     * @param context Context dell'applicazione, necessario per ottenere il `ContentResolver`.
     * @param directoryUri L'URI della directory scelta dall'utente tramite `OpenDocumentTree`.
     * @param fileName Il nome del file da creare.
     * @return L'URI del file CSV creato con successo, o `null` in caso di errore.
     */
    fun exportErrorsToCsv(
        errors: List<RowImportError>,
        context: Context,
        directoryUri: Uri,
        fileName: String = "import_errors_${System.currentTimeMillis()}.csv" // Nome file unico
    ): Uri? {
        try {
            // **FIX: Utilizza DocumentsContract per creare un nuovo documento (file).**
            // Questo è il modo corretto per lavorare con le URI dell'albero di documenti (Document Tree).
            val fileUri = DocumentsContract.createDocument(
                context.contentResolver,
                directoryUri,
                "text/csv", // Mime type del file
                fileName
            )

            // Se la creazione del file fallisce (es. per permessi mancanti), fileUri sarà null.
            fileUri?.let { uri ->
                // Apri un output stream verso l'URI del file appena creato.
                // Il blocco `use` garantisce che lo stream venga chiuso automaticamente.
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    // Usa un BufferedWriter per scrivere in modo efficiente.
                    outputStream.bufferedWriter().use { out ->
                        // Scrivi l'intestazione del CSV.
                        out.write("RowNumber;RowContent;ErrorReason\n")
                        // Itera su ogni errore per scriverlo come una riga nel file.
                        errors.forEach { error ->
                            // Prepara il contenuto della riga per essere CSV-safe:
                            // 1. Unisci gli elementi con un separatore.
                            // 2. Rimuovi le nuove righe per non rompere la struttura del file.
                            // 3. Esegui l'escape delle virgolette doppie raddoppiandole.
                            val rowContent = error.rowContent
                                .joinToString(separator = "|") // Uso '|' per essere sicuri
                                .replace("\n", " ")
                                .replace("\"", "\"\"")

                            val errorReason = error.errorReason.replace("\"", "\"\"")

                            // Scrivi la riga completa, racchiudendo i campi di testo tra virgolette.
                            out.write("${error.rowNumber};\"$rowContent\";\"$errorReason\"\n")
                        }
                    }
                }
                // Restituisce l'URI del file creato con successo.
                return uri
            }
        } catch (e: IOException) {
            // Gestisce specificamente gli errori di Input/Output.
            e.printStackTrace()
        } catch (e: Exception) {
            // Gestisce altri errori imprevisti.
            e.printStackTrace()
        }
        // Se qualcosa va storto durante la creazione o la scrittura, restituisce null.
        return null
    }
}
