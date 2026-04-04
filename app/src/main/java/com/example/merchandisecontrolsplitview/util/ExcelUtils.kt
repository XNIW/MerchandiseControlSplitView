package com.example.merchandisecontrolsplitview.util

import android.content.Context
import android.net.Uri
import com.example.merchandisecontrolsplitview.R
import org.apache.poi.ss.usermodel.CellType
import java.text.Normalizer
import kotlin.math.roundToLong
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.DataFormatter

fun readAndAnalyzeExcel(
    context: Context,
    uri: Uri,
    allowEmptyTabularResult: Boolean = false
): Triple<List<String>, List<List<String>>, List<String>> {
    val rows = mutableListOf<List<String>>()
    val emptyFileMessage = context.getString(R.string.error_file_empty_or_invalid)
    val inputStream = context.contentResolver.openInputStream(uri)
        ?: throw ExcelInputStreamUnavailableException()

    inputStream.use { inStream ->
        val bytes = inStream.readBytes()
        if (bytes.isEmpty()) {
            throw IllegalArgumentException(emptyFileMessage)
        }

        if (looksLikeExcelHtml(bytes)) {
            // Fallback HTML → righe
            rows += normalizeTabularRows(parseExcelHtmlToRows(bytes))
        } else {
            // Flusso “buono”: BIFF8/XLSX con Apache POI
            createWorkbookWithLegacyFallback(bytes).use { wb ->
                rows += readPoiRows(wb.getSheetAt(0))
            }
        }
    }

    val analysis = analyzeRows(context, rows)
    rows.clear()
    val hasEmptyTabularResult = analysis.first.isEmpty() || analysis.second.isEmpty()
    if (hasEmptyTabularResult) {
        if (!allowEmptyTabularResult) {
            throw IllegalArgumentException(emptyFileMessage)
        }
        return Triple(emptyList(), emptyList(), emptyList())
    }
    return analysis
}


fun getLocalizedHeader(context: Context, key: String): String {
    val resolvedKey = when (val rawKey = key.trim()) {
        "RetailPrice" -> "retailPrice"
        "prevPurchase" -> "oldPurchasePrice"
        "prevRetail" -> "oldRetailPrice"
        else -> canonicalExcelHeaderKey(rawKey) ?: rawKey
    }

    return when (resolvedKey) {
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
        "complete" -> context.getString(R.string.header_complete)
        "secondProductName" -> context.getString(R.string.header_second_product_name)
        "rowNumber" -> context.getString(R.string.header_row_number)
        "discount" -> context.getString(R.string.header_discount)
        "discountedPrice" -> context.getString(R.string.header_discounted_price)
        "realQuantity" -> context.getString(R.string.header_real_quantity)
        "category"     -> context.getString(R.string.header_category)
        "supplierId" -> context.getString(R.string.header_supplier_id)
        "categoryId" -> context.getString(R.string.header_category_id)
        "stockQuantity" -> context.getString(R.string.header_stock_quantity)
        else           -> resolvedKey // fallback: preserva header sconosciuti / interop custom
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

private fun looksLikeExcelHtml(bytes: ByteArray): Boolean {
    // Ispezione “leggera” dell’header
    val head = bytes.copyOfRange(0, minOf(4096, bytes.size))
        .toString(Charsets.ISO_8859_1) // non forzare UTF-8, qui basta il pattern
        .lowercase()
    return head.contains("<html") ||
            head.contains("mso-application") ||
            head.contains("office:excel") ||
            head.contains("<table")
}

/** Converte la tabella principale dell’HTML di Excel in List<List<String>>.
 *  Gestisce colspan/rowspan e rimuove NBSP/br. */
private fun parseExcelHtmlToRows(bytes: ByteArray): List<List<String>> {
    // Lascia fare a Jsoup il rilevamento charset dal <meta>
    val doc = Jsoup.parse(ByteArrayInputStream(bytes), null, "")
    val table = doc.select("table")
        .maxByOrNull { t -> t.select("tr").size * t.select("th,td").size }
        ?: return emptyList()

    val grid = mutableListOf<MutableList<String>>()
    // colonne “occupate” da rowspan provenienti da righe precedenti:
    // colIndex -> (righe residue, testo)
    val carry = mutableMapOf<Int, Pair<Int, String>>()

    for (tr in table.select("tr")) {
        val row = mutableListOf<String>()
        var col = 0

        // Pre-riempi con i valori riportati dai rowspan
        while (carry[col] != null) {
            val (rest, text) = carry[col]!!
            row.add(text)
            if (rest <= 1) carry.remove(col) else carry[col] = (rest - 1) to text
            col++
        }

        for (cell in tr.select("th,td")) {
            // Salta eventuali colonne già occupate da rowspan
            while (carry[col] != null) {
                val (rest, text) = carry[col]!!
                row.add(text)
                if (rest <= 1) carry.remove(col) else carry[col] = (rest - 1) to text
                col++
            }

            val raw = cell.text().replace('\u00A0', ' ').trim() // NBSP→spazio
            val text = raw.replace(Regex("\\s*\\n\\s*"), " ")
            val colspan = cell.attr("colspan").toIntOrNull() ?: 1
            val rowspan = cell.attr("rowspan").toIntOrNull() ?: 1

            repeat(colspan) { k ->
                row.add(text)
                if (rowspan > 1) {
                    carry[col + k] = (rowspan - 1) to text
                }
            }
            col += colspan
        }

        // Completa con eventuali carry rimasti immediatamente dopo
        while (carry[col] != null) {
            val (rest, text) = carry[col]!!
            row.add(text)
            if (rest <= 1) carry.remove(col) else carry[col] = (rest - 1) to text
            col++
        }

        grid.add(row)
    }

    // Normalizza larghezze righe (rettangolo)
    val maxCols = grid.maxOfOrNull { it.size } ?: 0
    return grid.map { r ->
        if (r.size < maxCols) (r + MutableList(maxCols - r.size) { "" }) else r
    }
}

internal fun normalizeExcelHeader(s: String) = Normalizer
    .normalize(s, Normalizer.Form.NFD)
    .replace("\\p{M}+".toRegex(), "")
    .trim()
    .replace(" ", "")
    .replace("_", "")
    .replace(Regex("[^\\p{L}\\p{Nd}]"), "")
    .lowercase()

internal val KNOWN_EXCEL_HEADER_ALIASES: Map<String, List<String>> = mapOf(
    "barcode" to listOf("barcode", "条码", "ean", "bar code", "codice a barre", "código de barras", "codigo de barras", "código barras", "codigo barras", "co.barra", "条形码", "Código de barras", "cod.barra", "cod barra", "codbarra", "cod.barras", "codbarras"),
    "quantity" to listOf("quantity", "数量", "qty", "quantità", "amount", "cantidad", "número", "numero", "número de unidades", "numero de unidades", "unds.", "总数量", "stock", "stockquantity", "giacenza", "scorte", "库存", "库存数量", "Existencias", "Stock Quantity", "cantid"),
    "purchasePrice" to listOf("purchaseprice", "New Purchase Price", "purchase_price", "进价", "buy price", "prezzo acquisto", "cost", "unit price", "prezzo", "precio de compra", "precio compra", "costo", "precio unitario", "precio adquisición", "precio", "v. unit. bruto", "单价", "价格", "原价", "售价", "新进价", "Nuovo prezzo acquisto", "Nuevo Precio de Compra", "New Purchase Price", "折前单价(含税)", "pre/u"),
    "totalPrice" to listOf("totalprice", "total_price", "总价", "totale", "importo", "price total", "precio total", "importe", "total", "importe total", "importe final", "subtotal", "subtotal bruto", "合计", "金额", "总计", "importe"),
    "productName" to listOf("productname", "product_name", "品名", "descrizione", "name", "nome", "description", "nombre del producto", "nombre producto", "producto", "descripción", "descripcion", "nombre", "产品名1", "产品品名", "商品名1", "Nome prodotto", "Nombre del producto", "Product name", "商品名称", "外文描述", "articulo", "artículo"),
    "secondProductName" to listOf("productname2", "product_name2", "品名2", "descrizione2", "name2", "nome2", "description2", "nombre del producto2", "nombre producto2", "producto2", "descripción2", "descripcion2", "nombre2", "产品名2", "产品品名2", "商品名2", "Secondo nome prodotto", "Segundo nombre del producto", "Second Product Name", "西语名称", "物料描述", "second name", "secondname", "nombre 2", "nombre2", "nome 2", "nome2", "product name 2", "productname2"),
    "itemNumber" to listOf("itemnumber", "item_number", "货号", "codice", "code", "articolo", "número de artículo", "numero de artículo", "número de producto", "numero de producto", "código", "referencia", "产品货号", "编号","codice articolo","Código del artículo","Item code", "编码", "短码", "ref.cajas","codice prodotto", "codiceprodotto", "product code", "productcode", "código de producto", "codigodeproducto"),
    "supplier" to listOf("supplier", "供应商", "fornitore", "vendor", "provider", "fornitore/azienda", "proveedor", "empresa proveedora", "vendedor", "distribuidor", "fabricante", "Proveedor"),
    "rowNumber" to listOf("no", "n.", "№", "row", "rowno", "rownumber", "serial", "serialnumber", "progressivo", "numeroriga", "num. riga", "número de fila", "número", "numero", "序号", "编号", "编号序号", "序列号", "行号", "#"),
    "discount" to listOf("discount", "sconto", "折扣", "descuento", "rabatt", "sc.", "dcto", "scnto", "scnt.", "rebaja", "remise", "D%", "D.%", "dto%"),
    "discountedPrice" to listOf("discountedprice", "prezzoscontato", "precio con descuento", "precio descontado", "折后价", "prezzo scontato", "precio rebajado", "rebate price", "after discount price", "final price", "prezzo finale", "售价", "Pre.-D%", "折后单价(含税)"),
    "retailPrice" to listOf("retailprice", "retail_price", "零售价", "prezzo vendita", "prezzo retail", "sale price", "listino", "precio de venta", "precio venta", "precio al público", "precio retail", "precio al por menor", "Nuovo Prezzo vendita", "新零售价", "Nuevo precio de venta", "New retail price"),
    "realQuantity" to listOf("实点数量", "Counted quantity", "Quantità contata", "Cantidad contada"),
    "category" to listOf("category", "categoria", "reparto", "department", "分类", "类别", "categoría"),
    "oldPurchasePrice" to listOf("oldpurchaseprice", "prezzovecchioacquisto", "prezzoprecedenteacquisto", "acquistoprec","previouspurchaseprice","Prezzo vecchio acquisto", "旧进价", "Precio de compra anterior", "Old purchase price", "Purchase (Old)", "Acquisto (Vecchio)", "Compra (Antiguo)", "进价（旧）"),
    "oldRetailPrice" to listOf("oldretailprice", "prezzovecchiovendita", "prezzoprecedentevendita", "venditaprec", "previousretailprice", "Prezzo vecchio vendita", "旧零售价", "Precio de venta anterior", "Old retail price", "Retail (Old)", "Vendita (Vecchio)", "Venta (Antiguo)", "售价（旧）")
)

private val NORMALIZED_EXCEL_HEADER_LOOKUP: Map<String, String> = buildMap {
    KNOWN_EXCEL_HEADER_ALIASES.forEach { (canonical, aliases) ->
        put(normalizeExcelHeader(canonical), canonical)
        aliases.forEach { alias ->
            put(normalizeExcelHeader(alias), canonical)
        }
    }
}

internal fun canonicalExcelHeaderKey(rawHeader: String): String? {
    val normalized = normalizeExcelHeader(rawHeader)
    if (normalized.isBlank()) return null
    return NORMALIZED_EXCEL_HEADER_LOOKUP[normalized]
}

private fun normalizeHeader(s: String) = normalizeExcelHeader(s)

private data class PrunedColumnsResult(
    val header: MutableList<String>,
    val headerSource: MutableList<String>,
    val dataRows: List<List<String>>,
    val oldToNewIndex: Map<Int, Int>
)

private fun normalizeTabularRows(rows: List<List<String>>): List<List<String>> {
    return rows.map { row -> row.dropLastWhile { it.isEmpty() } }
        .filter { row -> row.any { it.isNotBlank() } }
}

private fun readPoiRows(sheet: Sheet): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    sheet.forEach { row ->
        val temp = mutableListOf<String>()
        val last = row.lastCellNum.toInt().coerceAtLeast(0)
        for (i in 0 until last) {
            val cell = row.getCell(i)
            val txt = when {
                cell == null -> ""
                cell.cellType == CellType.STRING -> {
                    val rawValue = cell.stringCellValue ?: ""
                    if (rawValue.trim().startsWith("$")) {
                        val cleanedValue = rawValue.trim().replace("$", "").replace(",", "")
                        cleanedValue.toDoubleOrNull()?.roundToLong()?.toString() ?: rawValue
                    } else rawValue
                }
                cell.cellType == CellType.NUMERIC -> {
                    val n = cell.numericCellValue
                    if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString()
                }
                else -> cell.toString()
            }
            temp.add(txt.trim())
        }
        rows.add(temp)
    }
    return normalizeTabularRows(rows)
}

private fun pruneTotallyEmptyColumns(
    header: List<String>,
    headerSource: List<String>,
    dataRows: List<List<String>>
): PrunedColumnsResult {
    if (header.isEmpty()) {
        return PrunedColumnsResult(
            header = mutableListOf(),
            headerSource = mutableListOf(),
            dataRows = dataRows,
            oldToNewIndex = emptyMap()
        )
    }

    val nonEmptyCols = header.indices.filter { col ->
        dataRows.any { row -> row.getOrNull(col)?.isNotBlank() == true }
    }
    val oldToNewIndex = nonEmptyCols.withIndex().associate { it.value to it.index }

    return PrunedColumnsResult(
        header = nonEmptyCols.map { header[it] }.toMutableList(),
        headerSource = nonEmptyCols.map { headerSource[it] }.toMutableList(),
        dataRows = dataRows.map { row -> nonEmptyCols.map { idx -> row.getOrNull(idx) ?: "" } },
        oldToNewIndex = oldToNewIndex
    )
}

private fun analyzeRows(
    context: Context,
    rows: List<List<String>>
): Triple<List<String>, List<List<String>>, List<String>> {
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

    // --- Alias (uguali a prima) ---
    val possibleNames = KNOWN_EXCEL_HEADER_ALIASES

    val headerMap = mutableMapOf<String, Int>()
    val usedCols = mutableSetOf<Int>()

    if (hasHeader) {
        val prioritizedKeys = listOf("retailPrice", "purchasePrice") +
                possibleNames.keys.filterNot { it == "retailPrice" || it == "purchasePrice" }

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
                header[foundIdx] = key
                headerSource[foundIdx] = "alias"
            }
        }

        val newHeaderMap = mutableMapOf<String, Int>()
        val newUsedCols = mutableSetOf<Int>()
        val prunedColumns = pruneTotallyEmptyColumns(header, headerSource, dataRows)
        header = prunedColumns.header
        headerSource = prunedColumns.headerSource
        dataRows = prunedColumns.dataRows
        for ((k, oldIdx) in headerMap) {
            prunedColumns.oldToNewIndex[oldIdx]?.let { newIdx ->
                newHeaderMap[k] = newIdx
                newUsedCols.add(newIdx)
            }
        }
        headerMap.clear(); headerMap.putAll(newHeaderMap)
        usedCols.clear(); usedCols.addAll(newUsedCols)
    } else {
        val prunedColumns = pruneTotallyEmptyColumns(header, headerSource, dataRows)
        header = prunedColumns.header
        headerSource = prunedColumns.headerSource
        dataRows = prunedColumns.dataRows
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

    // Pattern principali
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
                    "retailPrice", "discountedPrice" -> {
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

    fun ensureColumn(
        key: String,
        insertAt: Int,
        header: MutableList<String>,
        headerSource: MutableList<String>,
        dataRows: List<List<String>>
    ): Triple<MutableList<String>, List<List<String>>, MutableList<String>> {
        if (!header.contains(key)) {
            header.add(insertAt, key)
            headerSource.add(insertAt, "generated")
            val newRows = dataRows.map { row ->
                val m = row.toMutableList()
                m.add(insertAt, "")
                m
            }
            return Triple(header, newRows, headerSource)
        }
        return Triple(header, dataRows, headerSource)
    }

    // Enforce colonne minime
    run {
        val pos = header.indexOf("itemNumber").let { if (it >= 0) it + 1 else 0 }
        val triple = ensureColumn("barcode", pos, header, headerSource, dataRows)
        header = triple.first; dataRows = triple.second; headerSource = triple.third
    }
    run {
        val afterBarcode = header.indexOf("barcode").let { if (it >= 0) it + 1 else -1 }
        val pos = when {
            afterBarcode >= 0 -> afterBarcode
            header.contains("itemNumber") -> header.indexOf("itemNumber") + 1
            else -> header.size
        }
        val triple = ensureColumn("productName", pos, header, headerSource, dataRows)
        header = triple.first; dataRows = triple.second; headerSource = triple.third
    }
    run {
        val afterQty = header.indexOf("quantity").let { if (it >= 0) it + 1 else -1 }
        val pos = when {
            afterQty >= 0 -> afterQty
            header.contains("productName") -> header.indexOf("productName") + 1
            else -> header.size
        }
        val triple = ensureColumn("purchasePrice", pos, header, headerSource, dataRows)
        header = triple.first; dataRows = triple.second; headerSource = triple.third
    }

    val summaryTokens = listOf(
        "合计","总计","小计","汇总","合計","總計","小計","總結","总额",
        "subtotal","total","totale","tot.","sommario","resumen","sum"
    ).map { it.lowercase() }

    fun isSummaryRow(row: List<String>): Boolean {
        fun valAt(key: String) = row.getOrNull(headerMap[key] ?: -1)?.trim().orEmpty()
        val name = valAt("productName")
        val item = valAt("itemNumber")
        val code = valAt("barcode")
        val firstText = row.firstOrNull { it.isNotBlank() && it.replace(",", ".").toDoubleOrNull() == null }
            ?.trim()?.lowercase().orEmpty()
        val looksLikeToken = summaryTokens.any { tok -> firstText.startsWith(tok) || name.lowercase().startsWith(tok) }
        val manyNumbers = row.count { it.replace(",", ".").toDoubleOrNull() != null } >= 2
        val lacksIdentity = code.isBlank() && item.isBlank() && name.length < 3
        return looksLikeToken && manyNumbers && lacksIdentity
    }

    dataRows = dataRows.filterNot { isSummaryRow(it) }

    return Triple(header, dataRows, headerSource)
}

fun analyzePoiSheet(context: Context, sheet: Sheet)
        : Triple<List<String>, List<List<String>>, List<String>> {
    val fmt = DataFormatter()
    val rows = mutableListOf<List<String>>()
    for (r in 0..sheet.lastRowNum) {
        val row = sheet.getRow(r) ?: continue
        val last = row.lastCellNum.toInt().coerceAtLeast(0)
        rows += (0 until last).map { c -> fmt.formatCellValue(row.getCell(c)).trim() }
    }
    return analyzeRows(context, normalizeTabularRows(rows))
}
