package com.example.merchandisecontrolsplitview.util

import android.content.Context
import android.net.Uri
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.WorkbookFactory

fun readAndAnalyzeExcel(
    context: Context,
    uri: Uri
): Pair<List<String>, List<List<String>>> {
    // Funzione per normalizzare nomi colonne
    fun normalizeHeader(s: String) = s
        .trim()
        .replace(" ", "")
        .replace("_", "")
        .lowercase()

    // 1. Lettura grezza di tutte le righe
    val rows = mutableListOf<List<String>>()
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val wb = WorkbookFactory.create(stream)
        val sheet = wb.getSheetAt(0)
        sheet.forEach { row ->
            val temp = mutableListOf<String>()
            val last = row.lastCellNum.toInt()
            for (i in 0 until last) {
                val cell = row.getCell(i)
                val txt = when {
                    cell == null -> ""
                    cell.cellType == CellType.STRING  -> cell.stringCellValue ?: ""
                    cell.cellType == CellType.NUMERIC -> {
                        val n = cell.numericCellValue
                        if (n == n.toLong().toDouble())
                            n.toLong().toString()
                        else
                            n.toBigDecimal().toPlainString()
                    }
                    cell.cellType == CellType.BOOLEAN -> cell.booleanCellValue.toString()
                    else -> ""
                }.trim()
                temp.add(txt)
            }
            val trimmed = temp.dropLastWhile { it.isEmpty() }
            if (!trimmed.all { it.isEmpty() }) {
                rows.add(trimmed)
            }
        }
        wb.close()
    }

    // Trova la prima riga "intestazione dati"
    var dataRowIdx = -1
    for ((idx, row) in rows.withIndex()) {
        val numericCount = row.count { it.replace(",", ".").toDoubleOrNull() != null }
        val textCount = row.count { it.isNotBlank() && it.replace(",", ".").toDoubleOrNull() == null }
        if (numericCount >= 3 && textCount >= 1) {
            dataRowIdx = idx
            break
        }
    }
    if (dataRowIdx <= 0 || dataRowIdx >= rows.size) {
        return Pair(emptyList(), emptyList())
    }

    // Intestazione e dati
    val header = rows[dataRowIdx - 1].toMutableList()
    val dataRows = rows.drop(dataRowIdx)
        .filter { row ->
            val numericCount = row.count { it.replace(",", ".").toDoubleOrNull() != null }
            val textCount = row.count { it.isNotBlank() && it.replace(",", ".").toDoubleOrNull() == null }
            numericCount >= 3 && textCount >= 1
        }
    if (dataRows.isEmpty()) return Pair(header, dataRows)

    val colCount = header.size
    val threshold = (dataRows.size * 0.5).toInt()

    // 1. Primo passaggio: ricerca per nome colonna (con normalizzazione!)
    val possibleNames = mapOf(
        "barcode" to listOf(
            "barcode", "条码", "ean", "bar code", "codice a barre",
            "código de barras", "codigo de barras", "código barras", "codigo barras"
        ),
        "quantity" to listOf(
            "quantity", "数量", "qty", "quantità", "amount",
            "cantidad", "número", "numero", "número de unidades", "numero de unidades"
        ),
        "purchasePrice" to listOf(
            "purchaseprice", "New Purchase Price", "purchase_price", "进价", "buy price", "prezzo acquisto", "cost", "unit price", "prezzo",
            "precio de compra", "precio compra", "costo", "precio unitario", "precio adquisición"
        ),
        "retailPrice" to listOf(
            "retailprice", "New Retail Price", "retail_price", "零售价", "prezzo vendita", "prezzo retail", "sale price", "listino",
            "precio de venta", "precio venta", "precio al público", "precio retail", "precio al por menor"
        ),
        "totalPrice" to listOf(
            "totalprice", "total_price", "总价", "totale", "importo", "price total",
            "precio total", "importe", "total", "importe total", "importe final"
        ),
        "productName" to listOf(
            "productname", "product_name", "品名", "descrizione", "name", "nome", "description",
            "nombre del producto", "nombre producto", "producto", "descripción", "descripcion", "nombre"
        ),
        "itemNumber" to listOf(
            "itemnumber", "item_number", "货号", "codice", "code", "articolo", "sku",
            "número de artículo", "numero de artículo", "número de producto", "numero de producto", "código", "codigo", "referencia"
        ),
        "supplier" to listOf(
            "supplier", "供应商", "fornitore", "vendor", "provider", "fornitore/azienda",
            "proveedor", "empresa proveedora", "vendedor", "distribuidor", "fabricante"
        )
    )

    val headerMap = mutableMapOf<String, Int>() // chiave -> indice
    val usedCols = mutableSetOf<Int>()
    for ((key, aliases) in possibleNames) {
        val foundIdx = header.indexOfFirst { colName ->
            val normCol = normalizeHeader(colName)
            aliases.any { alias -> normCol == normalizeHeader(alias) }
        }
        if (foundIdx >= 0) {
            headerMap[key] = foundIdx
            usedCols.add(foundIdx)
            header[foundIdx] = key // aggiorna header in inglese standard!
        }
    }

    // 2. Secondo passaggio: pattern recognition SOLO se la colonna non è stata trovata
    // — barcode (solo se non trovato prima)
    if (!headerMap.containsKey("barcode")) {
        for (col in 0 until colCount) {
            if (usedCols.contains(col)) continue
            val matches = dataRows.count { row ->
                val v = row.getOrNull(col)?.trim() ?: ""
                (v.length in listOf(8,12,13) && v.all(Char::isDigit))
            }
            if (matches >= threshold) {
                headerMap["barcode"] = col; usedCols.add(col); header[col] = "barcode"; break
            }
        }
    }
    // — quantity
    if (!headerMap.containsKey("quantity")) {
        for (col in 0 until colCount) {
            if (usedCols.contains(col)) continue
            val nums = dataRows.mapNotNull { it.getOrNull(col)?.replace(",", ".")?.toDoubleOrNull() }
            if (nums.isNotEmpty() && nums.all { it > 0 } && nums.size >= dataRows.size * 0.7) {
                headerMap["quantity"] = col; usedCols.add(col); header[col] = "quantity"; break
            }
        }
    }
    // — purchasePrice
    if (!headerMap.containsKey("purchasePrice")) {
        for (col in 0 until colCount) {
            if (usedCols.contains(col)) continue
            val nums = dataRows.mapNotNull { it.getOrNull(col)?.replace(",", ".")?.toDoubleOrNull() }
            if (
                nums.isNotEmpty() &&
                nums.all { it > 0 } &&
                nums.size >= dataRows.size * 0.7
            ) {
                headerMap["purchasePrice"] = col; usedCols.add(col); header[col] = "purchasePrice"; break
            }
        }
    }
    // — totalPrice
    if (!headerMap.containsKey("totalPrice")) {
        for (col in 0 until colCount) {
            if (usedCols.contains(col)) continue
            val matches = dataRows.count { row ->
                val tot = row.getOrNull(col)?.replace(",", ".")?.toDoubleOrNull() ?: return@count false
                tot > 0
            }
            if (matches >= dataRows.size * 0.7) {
                headerMap["totalPrice"] = col; usedCols.add(col); header[col] = "totalPrice"; break
            }
        }
    }
    // — retailPrice
    if (!headerMap.containsKey("retailPrice")) {
        for (col in 0 until colCount) {
            if (usedCols.contains(col)) continue
            val nums = dataRows.mapNotNull { it.getOrNull(col)?.replace(",", ".")?.toDoubleOrNull() }
            if (
                nums.isNotEmpty() &&
                nums.all { it > 0 } &&
                nums.size >= dataRows.size * 0.7
            ) {
                headerMap["retailPrice"] = col; usedCols.add(col); header[col] = "retailPrice"; break
            }
        }
    }
    // — productName
    if (!headerMap.containsKey("productName")) {
        for (col in 0 until colCount) {
            if (usedCols.contains(col)) continue
            val matches = dataRows.count { row ->
                val v = row.getOrNull(col)?.trim() ?: ""
                v.length >= 3 && v.any { !it.isDigit() }
            }
            if (matches >= dataRows.size * 0.5) {
                headerMap["productName"] = col; usedCols.add(col); header[col] = "productName"; break
            }
        }
    }
    // — itemNumber
    if (!headerMap.containsKey("itemNumber")) {
        for (col in 0 until colCount) {
            if (usedCols.contains(col)) continue
            val matches = dataRows.count { row ->
                val v = row.getOrNull(col)?.trim() ?: ""
                v.length in 4..12 && (v.any { it.isDigit() } || v.any { it.isLetter() })
            }
            if (matches >= dataRows.size * 0.5) {
                headerMap["itemNumber"] = col; usedCols.add(col); header[col] = "itemNumber"; break
            }
        }
    }
    // — supplier
    if (!headerMap.containsKey("supplier")) {
        for (col in 0 until colCount) {
            if (usedCols.contains(col)) continue
            val matches = dataRows.count { row ->
                val v = row.getOrNull(col)?.trim() ?: ""
                v.length >= 3
            }
            if (matches >= dataRows.size * 0.5) {
                headerMap["supplier"] = col; usedCols.add(col); header[col] = "supplier"; break
            }
        }
    }

    // Filtra colonne completamente vuote
    val nonEmptyCols = header.indices.filter { col ->
        dataRows.any { row -> row.getOrNull(col)?.isNotBlank() == true }
    }
    val filteredHeader = nonEmptyCols.map { header[it] }
    val filteredDataRows = dataRows.map { row ->
        nonEmptyCols.map { idx -> row.getOrNull(idx) ?: "" }
    }

    return Pair(filteredHeader, filteredDataRows)
}