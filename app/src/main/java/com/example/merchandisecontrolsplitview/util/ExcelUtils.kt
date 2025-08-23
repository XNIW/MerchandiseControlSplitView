package com.example.merchandisecontrolsplitview.util

import android.content.Context
import android.net.Uri
import com.example.merchandisecontrolsplitview.R
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.text.Normalizer
import kotlin.math.roundToLong

fun readAndAnalyzeExcel(
    context: Context,
    uri: Uri
): Triple<List<String>, List<List<String>>, List<String>> {
    fun normalizeHeader(s: String) = Normalizer
        .normalize(s, Normalizer.Form.NFD)
        .replace("\\p{M}+".toRegex(), "") // rimuove accenti
        .trim()
        .replace(" ", "")
        .replace("_", "")
        .replace(Regex("[^\\p{L}\\p{Nd}]"), "")
        .lowercase()

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
                    cell.cellType == CellType.STRING -> {
                        val rawValue = cell.stringCellValue ?: ""
                        // Controlla se il valore inizia con '$'
                        if (rawValue.trim().startsWith("$")) {
                            // Pulisce il valore da '$' e ','
                            val cleanedValue = rawValue.trim().replace("$", "").replace(",", "")
                            try {
                                // Converte in numero, arrotonda e restituisce come stringa
                                cleanedValue.toDouble().roundToLong().toString()
                            } catch (_: NumberFormatException) {
                                rawValue // In caso di errore, mantiene il valore originale
                            }
                        } else {
                            rawValue // Altrimenti, restituisce il valore originale
                        }
                    }
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
    val hasHeader = (dataRowIdx > 0 && dataRowIdx < rows.size)

    var header: MutableList<String>
    var headerSource: MutableList<String>
    var dataRows: List<List<String>>

    if (hasHeader) {
        header = rows[dataRowIdx - 1].toMutableList()
        headerSource = MutableList(header.size) { "unknown" }
        dataRows = rows.drop(dataRowIdx).filter { row ->
            val numericCount = row.count { it.replace(",", ".").toDoubleOrNull() != null }
            val textCount = row.count { it.isNotBlank() && it.replace(",", ".").toDoubleOrNull() == null }
            numericCount >= 3 && textCount >= 1
        }
    } else {
        val colCount = rows.maxOfOrNull { it.size } ?: 0
        header = (1..colCount).map { "${context.getString(R.string.generated_column_prefix)} $it" }.toMutableList()
        headerSource = MutableList(header.size) { "generated" }
        dataRows = rows
    }
    if (dataRows.isEmpty()) return Triple(header, dataRows, headerSource)

    // --- FIX: Mappa degli alias corretta e allineata ---
    val possibleNames = mapOf(
        "barcode" to listOf("barcode", "条码", "ean", "bar code", "codice a barre", "código de barras", "codigo de barras", "código barras", "codigo barras", "co.barra", "条形码", "Código de barras"),
        "quantity" to listOf("quantity", "数量", "qty", "quantità", "amount", "cantidad", "número", "numero", "número de unidades", "numero de unidades", "unds.", "总数量", "stock", "stockquantity", "giacenza", "scorte", "库存", "库存数量", "Existencias", "Stock Quantity"),
        "purchasePrice" to listOf("purchaseprice", "New Purchase Price", "purchase_price", "进价", "buy price", "prezzo acquisto", "cost", "unit price", "prezzo", "precio de compra", "precio compra", "costo", "precio unitario", "precio adquisición", "precio", "v. unit. bruto", "单价", "价格", "原价", "售价", "新进价", "Nuovo prezzo acquisto", "Nuevo Precio de Compra", "New Purchase Price", "折前单价(含税)"),
        "totalPrice" to listOf("totalprice", "total_price", "总价", "totale", "importo", "price total", "precio total", "importe", "total", "importe total", "importe final", "subtotal", "subtotal bruto", "合计", "金额", "总计"),
        "productName" to listOf("productname", "product_name", "品名", "descrizione", "name", "nome", "description", "nombre del producto", "nombre producto", "producto", "descripción", "descripcion", "nombre", "产品名1", "产品品名", "商品名1", "Nome prodotto", "Nombre del producto", "Product name", "商品名称", "外文描述"),
        "secondProductName" to listOf("productname2", "product_name2", "品名2", "descrizione2", "name2", "nome2", "description2", "nombre del producto2", "nombre producto2", "producto2", "descripción2", "descripcion2", "nombre2", "产品名2", "产品品名2", "商品名2", "Secondo nome prodotto", "Segundo nombre del producto", "Second Product Name", "西语名称", "物料描述"),
        "itemNumber" to listOf("itemnumber", "item_number", "货号", "codice", "code", "articolo", "número de artículo", "numero de artículo", "número de producto", "numero de producto", "código", "referencia", "产品货号", "编号","codice articolo","Código del artículo","Item code", "编码", "短码"),
        "supplier" to listOf("supplier", "供应商", "fornitore", "vendor", "provider", "fornitore/azienda", "proveedor", "empresa proveedora", "vendedor", "distribuidor", "fabricante", "Proveedor"),
        "rowNumber" to listOf("no", "n.", "№", "row", "rowno", "rownumber", "serial", "serialnumber", "progressivo", "numeroriga", "num. riga", "número de fila", "número", "numero", "序号", "编号", "编号序号", "序列号", "行号", "#"),
        "discount" to listOf("discount", "sconto", "折扣", "descuento", "rabatt", "sc.", "dcto", "scnto", "scnt.", "rebaja", "remise", "D%", "D.%"),
        "discountedPrice" to listOf("discountedprice", "prezzoscontato", "precio con descuento", "precio descontado", "折后价", "prezzo scontato", "precio rebajado", "rebate price", "after discount price", "final price", "prezzo finale", "售价", "Pre.-D%", "折后单价(含税)"),
        "retailPrice" to listOf("retailprice", "retail_price", "零售价", "prezzo vendita", "prezzo retail", "sale price", "listino", "precio de venta", "precio venta", "precio al público", "precio retail", "precio al por menor", "Nuovo Prezzo vendita", "新零售价", "Nuevo precio de venta", "New retail price"),
        "realQuantity" to listOf("实点数量", "Counted quantity", "Quantità contata", "Cantidad contada"),
        "category" to listOf("category", "categoria", "reparto", "department", "分类", "类别", "categoría"),
        "oldpurchasePrice" to listOf("oldpurchaseprice", "prezzovecchioacquisto", "prezzoprecedenteacquisto", "acquistoprec","previouspurchaseprice","Prezzo vecchio acquisto", "旧进价", "Precio de compra anterior", "Old purchase price"),
        "oldretailPrice" to listOf("oldretailprice", "prezzovecchiovendita", "prezzoprecedentevendita", "venditaprec", "previousretailprice", "Prezzo vecchio vendita", "旧零售价", "Precio de venta anterior", "Old retail price")
    )

    val headerMap = mutableMapOf<String, Int>()
    val usedCols = mutableSetOf<Int>()

    // Alias matching SOLO se header reale
    if (hasHeader) {
        // L'ordine è importante per evitare che un alias più generico "rubi" una colonna.
        // Diamo priorità alle chiavi più specifiche.
        val prioritizedKeys = listOf("RetailPrice", "purchasePrice") + possibleNames.keys.filterNot { it == "RetailPrice" || it == "purchasePrice" }

        for (key in prioritizedKeys) {
            val aliases = possibleNames[key] ?: continue
            val foundIdx = header.indexOfFirst { colName ->
                if (normalizeHeader(colName).isBlank()) false else {
                    val normCol = normalizeHeader(colName)
                    aliases.any { alias -> normCol == normalizeHeader(alias) }
                }
            }
            if (foundIdx >= 0 && !usedCols.contains(foundIdx)) {
                headerMap[key] = foundIdx
                usedCols.add(foundIdx)
                header[foundIdx] = key // aggiorna header in inglese standard!
                headerSource[foundIdx] = "alias"
            }
        }

        // Filtro colonne completamente vuote DOPO alias
        val nonEmptyCols = header.indices.filter { col ->
            dataRows.any { row -> row.getOrNull(col)?.isNotBlank() == true }
        }
        val emptyCols = header.indices.filter { col -> !nonEmptyCols.contains(col) }
        val colToHeader = headerMap.entries.associate { it.value to it.key }
        for (emptyCol in emptyCols) {
            val assignedHeader = colToHeader[emptyCol]
            if (assignedHeader != null) {
                headerMap.remove(assignedHeader)
            }
            usedCols.remove(emptyCol)
            headerSource[emptyCol] = "unknown"
        }
        val oldToNewIdx = nonEmptyCols.withIndex().associate { it.value to it.index }
        header = nonEmptyCols.map { header[it] }.toMutableList()
        headerSource = nonEmptyCols.map { headerSource[it] }.toMutableList()
        dataRows = dataRows.map { row -> nonEmptyCols.map { idx -> row.getOrNull(idx) ?: "" } }
        // Aggiorna headerMap e usedCols sugli indici nuovi
        val newHeaderMap = mutableMapOf<String, Int>()
        val newUsedCols = mutableSetOf<Int>()
        for ((k, oldIdx) in headerMap) {
            oldToNewIdx[oldIdx]?.let { newIdx ->
                newHeaderMap[k] = newIdx
                newUsedCols.add(newIdx)
            }
        }
        headerMap.clear()
        headerMap.putAll(newHeaderMap)
        usedCols.clear()
        usedCols.addAll(newUsedCols)
    }

    val colCount = header.size
    val threshold = (dataRows.size * 0.5).toInt()
    fun setIfFound(key: String, col: Int) {
        if (usedCols.contains(col)) return
        headerMap[key] = col
        usedCols.add(col)
        header[col] = key
        if (headerSource[col] != "alias") headerSource[col] = "pattern"
    }

    // --- Pattern recognition ---
    // (Il resto della logica di pattern recognition rimane invariato)
    val principali = listOf("itemNumber", "barcode", "productName", "quantity", "purchasePrice", "totalPrice")
    for (key in principali) {
        if (!headerMap.containsKey(key)) {
            when (key) {
                "barcode" -> {
                    for (col in 0 until colCount) {
                        if (usedCols.contains(col)) continue
                        val matches = dataRows.count { row ->
                            val v = row.getOrNull(col)?.trim() ?: ""
                            (v.length in listOf(8,12,13) && v.all(Char::isDigit))
                        }
                        if (matches >= threshold) { setIfFound("barcode", col); break }
                    }
                }
                "itemNumber" -> {
                    for (col in 0 until colCount) {
                        if (usedCols.contains(col)) continue
                        val matches = dataRows.count { row ->
                            val v = row.getOrNull(col)?.trim() ?: ""
                            v.length in 4..12 && (v.any { it.isDigit() } || v.any { it.isLetter() })
                        }
                        if (matches >= dataRows.size * 0.5) { setIfFound("itemNumber", col); break }
                    }
                }
                "quantity" -> {
                    for (col in 0 until colCount) {
                        if (usedCols.contains(col)) continue
                        val nums = dataRows.mapNotNull { it.getOrNull(col)?.replace(",", ".")?.toDoubleOrNull() }
                        if (nums.isNotEmpty() && nums.all { it > 0 } && nums.size >= dataRows.size * 0.7) {
                            setIfFound("quantity", col); break
                        }
                    }
                }
                "purchasePrice" -> {
                    for (col in 0 until colCount) {
                        if (usedCols.contains(col)) continue
                        val nums = dataRows.mapNotNull { it.getOrNull(col)?.replace(",", ".")?.toDoubleOrNull() }
                        if (nums.isNotEmpty() && nums.all { it > 0 } && nums.size >= dataRows.size * 0.7) {
                            setIfFound("purchasePrice", col); break
                        }
                    }
                }
                "totalPrice" -> {
                    val idxQuantity = headerMap["quantity"] ?: -1
                    val idxPurchase = headerMap["purchasePrice"] ?: -1
                    if (idxQuantity != -1 && idxPurchase != -1) {
                        for (col in 0 until colCount) {
                            if (usedCols.contains(col)) continue
                            val matches = dataRows.count { row ->
                                val quantity = parseNumber(row.getOrNull(idxQuantity))
                                val purchase = parseNumber(row.getOrNull(idxPurchase))
                                val tot      = parseNumber(row.getOrNull(col))
                                if (quantity == null || purchase == null || tot == null) return@count false
                                val expected = quantity * purchase
                                val epsilon = 0.10 * (expected.coerceAtLeast(1.0))
                                kotlin.math.abs(tot - expected) <= epsilon
                            }
                            if (matches >= dataRows.size * 0.7) {
                                setIfFound("totalPrice", col)
                                break
                            }
                        }
                    }
                }
                "productName" -> {
                    for (col in 0 until colCount) {
                        if (usedCols.contains(col)) continue
                        val matches = dataRows.count { row ->
                            val v = row.getOrNull(col)?.trim() ?: ""
                            v.length >= 3
                        }
                        if (matches >= dataRows.size * 0.5) { setIfFound("productName", col); break }
                    }
                }
            }
        }
    }

    if (!hasHeader) {
        val supplementari = listOf("retailPrice", "secondProductName", "supplier", "discount", "discountedPrice", "rowNumber")
        for (key in supplementari) {
            if (!headerMap.containsKey(key)) {
                when (key) {
                    "retailPrice", "discountedPrice" -> { // Rimosso purchasePrice per evitare duplicati
                        for (col in 0 until colCount) {
                            if (usedCols.contains(col)) continue
                            val nums = dataRows.mapNotNull { it.getOrNull(col)?.replace(",", ".")?.toDoubleOrNull() }
                            if (nums.isNotEmpty() && nums.all { it > 0 } && nums.size >= dataRows.size * 0.7) {
                                setIfFound(key, col); break
                            }
                        }
                    }
                    "secondProductName", "supplier" -> {
                        for (col in 0 until colCount) {
                            if (usedCols.contains(col)) continue
                            val matches = dataRows.count { row ->
                                val v = row.getOrNull(col)?.trim() ?: ""
                                v.length >= 3
                            }
                            if (matches >= dataRows.size * 0.5) { setIfFound(key, col); break }
                        }
                    }
                    "discount" -> {
                        for (col in 0 until colCount) {
                            if (usedCols.contains(col)) continue
                            val matches = dataRows.count { row ->
                                val v = row.getOrNull(col)?.trim() ?: ""
                                v.matches(Regex("""^(0[.,]\d{1,2})$""")) || v.matches(Regex("""^\d{1,2}%$"""))
                            }
                            if (matches >= threshold) { setIfFound("discount", col); break }
                        }
                    }
                    "rowNumber" -> {
                        for (col in 0 until colCount) {
                            if (usedCols.contains(col)) continue
                            val matches = dataRows.count { row ->
                                val v = row.getOrNull(col)?.trim() ?: ""
                                v.matches(Regex("""^\d+$""")) && v.length <= 6
                            }
                            if (matches >= threshold) { setIfFound("rowNumber", col); break }
                        }
                    }
                }
            }
        }
    }

    // --- Enforce colonne obbligatorie: crea 'barcode' se assente ---
    if (!header.contains("barcode")) {
        // Posiziona 'barcode' subito dopo 'itemNumber' se c'è, altrimenti in prima posizione
        val insertAt = header.indexOf("itemNumber").let { if (it >= 0) it + 1 else 0 }

        header.add(insertAt, "barcode")
        headerSource.add(insertAt, "generated")

        dataRows = dataRows.map { row ->
            val m = row.toMutableList()
            m.add(insertAt, "")
            m
        }
    }

    return Triple(header, dataRows, headerSource)
}


fun getLocalizedHeader(context: Context, key: String): String {
    return when (key) {
        "barcode"      -> context.getString(R.string.header_barcode)
        "quantity"     -> context.getString(R.string.header_quantity)
        "purchasePrice"-> context.getString(R.string.header_purchase_price)
        "retailPrice"  -> context.getString(R.string.header_retail_price)
        "totalPrice"   -> context.getString(R.string.header_total_price)
        "productName"  -> context.getString(R.string.header_product_name)
        "itemNumber"   -> context.getString(R.string.header_item_number)
        "supplier"     -> context.getString(R.string.header_supplier)
        "oldPurchasePrice" -> context.getString(R.string.header_old_purchase_price)
        "oldRetailPrice" -> context.getString(R.string.header_old_retail_price)
        "RetailPrice" -> context.getString(R.string.header_retail_price)
        "complete" -> context.getString(R.string.header_complete)
        "secondProductName" -> context.getString(R.string.header_second_product_name)
        "rowNumber" -> context.getString(R.string.header_row_number)
        "discount" -> context.getString(R.string.header_discount)
        "discountedPrice" -> context.getString(R.string.header_discounted_price)
        "realQuantity" -> context.getString(R.string.header_real_quantity)
        "category"     -> context.getString(R.string.header_category)
        else           -> key // fallback: mostra la chiave originale
    }
}

fun parseNumber(value: String?): Double? {
    if (value == null) return null
    val clean = value.trim()
    return when {
        clean.matches(Regex("^\\d{1,3}(\\.\\d{3})*,\\d+$")) ->
            clean.replace(".", "").replace(",", ".").toDoubleOrNull()
        clean.matches(Regex("^\\d{1,3}(,\\d{3})*\\.\\d+$")) ->
            clean.replace(",", "").toDoubleOrNull()
        else ->
            clean.replace(",", ".").toDoubleOrNull()
    }
}

/**
 * Formatta un numero Double? in una stringa per la visualizzazione.
 * - Arrotonda il numero all'intero più vicino.
 * - Restituisce "-" se il numero è nullo, ideale per le viste di sola lettura.
 */
fun formatNumberAsRoundedString(number: Double?): String {
    if (number == null) return "-"
    return number.roundToLong().toString()
}

/**
 * Formatta un numero Double? in una stringa per i campi di input.
 * - Arrotonda il numero all'intero più vicino.
 * - Restituisce una stringa vuota se il numero è nullo, ideale per i TextField.
 */
fun formatNumberAsRoundedStringForInput(number: Double?): String {
    if (number == null) return ""
    return number.roundToLong().toString()
}
