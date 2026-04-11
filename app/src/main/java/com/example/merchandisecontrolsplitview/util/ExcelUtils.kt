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
    val dataRows: List<List<String>>
)

private data class RowProfile(
    val index: Int,
    val nonBlankColumns: Set<Int>,
    val nonBlankCount: Int,
    val numericCount: Int,
    val textCount: Int,
    val aliasHits: Int
) {
    val looksDataLike: Boolean
        get() = nonBlankCount >= 4 && numericCount >= 2 && textCount >= 1
}

internal data class ExcelColumnCandidate(
    val columnIndex: Int,
    val score: Double,
    val reasons: List<String>
)

internal data class ExcelFieldDecisionTrace(
    val field: String,
    val selectedColumnIndex: Int?,
    val confidence: String,
    val reason: String,
    val candidates: List<ExcelColumnCandidate>
)

internal data class ExcelAnalysisTrace(
    val hasHeader: Boolean,
    val dataRowIdx: Int,
    val headerRows: List<Int>,
    val headerMode: String,
    val sampleSize: Int,
    val fieldDecisions: List<ExcelFieldDecisionTrace>
)

internal data class ExcelAnalysisResult(
    val header: List<String>,
    val dataRows: List<List<String>>,
    val headerSource: List<String>,
    val trace: ExcelAnalysisTrace
)

private data class ColumnSample(
    val columnIndex: Int,
    val values: List<String>,
    val nonBlankValues: List<String>,
    val numericValues: List<Double>,
    val medianNumeric: Double?,
    val numericRatio: Double,
    val integerRatio: Double,
    val decimalRawRatio: Double,
    val dominantLengthShare: Double,
    val dominantValueShare: Double,
    val digitLongRatio: Double,
    val shortCodeRatio: Double,
    val alphaNumericRatio: Double,
    val textRatio: Double,
    val longTextRatio: Double,
    val smallPositiveRatio: Double,
    val priceLikeMagnitudeRatio: Double
)

private data class HeaderDetection(
    val dataRowIdx: Int,
    val hasHeader: Boolean,
    val headerRows: List<Int>,
    val headerMode: String
)

private data class NumericPairDetection(
    val purchaseColumn: Int,
    val totalColumn: Int,
    val matchRatio: Double,
    val averageRelativeError: Double
)

private const val LEGACY_HEADER_ALIAS_FAST_PATH = 3
private const val MAX_HEADER_LOOKBACK_ROWS = 2
private const val MAX_PATTERN_SAMPLE_ROWS = 40
private const val MIN_PATTERN_EVIDENCE = 2
private const val MIN_PATTERN_SCORE = 0.45
private const val AMBIGUITY_MARGIN = 0.08
private const val MIN_ROW_NUMBER_LIKE_RATIO = 0.75

private fun minimumEvidenceFor(dataRowCount: Int): Int {
    return if (dataRowCount <= 1) 1 else MIN_PATTERN_EVIDENCE
}

private fun parseAnalysisNumber(value: String?): Double? {
    val clean = value?.trim()?.replace(" ", "").orEmpty()
    if (clean.isBlank()) return null
    if (clean.matches(Regex("^-?[1-9]\\d{0,2}(,\\d{3})+$"))) {
        return clean.replace(",", "").toDoubleOrNull()
    }
    if (clean.matches(Regex("^-?[1-9]\\d{0,2}(\\.\\d{3})+$"))) {
        return clean.replace(".", "").toDoubleOrNull()
    }
    return parseNumber(clean)
}

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
            dataRows = dataRows
        )
    }

    val nonEmptyCols = header.indices.filter { col ->
        dataRows.any { row -> row.getOrNull(col)?.isNotBlank() == true }
    }

    return PrunedColumnsResult(
        header = nonEmptyCols.map { header[it] }.toMutableList(),
        headerSource = nonEmptyCols.map { headerSource[it] }.toMutableList(),
        dataRows = dataRows.map { row -> nonEmptyCols.map { idx -> row.getOrNull(idx) ?: "" } }
    )
}

private fun ratio(numerator: Int, denominator: Int): Double {
    if (denominator <= 0) return 0.0
    return numerator.toDouble() / denominator.toDouble()
}

private fun <T> dominantShare(values: List<T>): Double {
    if (values.isEmpty()) return 0.0
    val maxCount = values.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
    return ratio(maxCount, values.size)
}

private fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[mid - 1] + sorted[mid]) / 2.0
    } else {
        sorted[mid]
    }
}

private fun countHeaderAliasHits(row: List<String>): Int {
    return row.mapNotNull(::canonicalExcelHeaderKey).distinct().size
}

private fun buildRowProfiles(rows: List<List<String>>): List<RowProfile> {
    return rows.mapIndexed { index, row ->
        val nonBlankColumns = row.mapIndexedNotNull { colIndex, value ->
            colIndex.takeIf { value.isNotBlank() }
        }.toSet()
        val numericCount = row.count { parseAnalysisNumber(it) != null }
        val textCount = row.count { it.isNotBlank() && parseAnalysisNumber(it) == null }
        RowProfile(
            index = index,
            nonBlankColumns = nonBlankColumns,
            nonBlankCount = nonBlankColumns.size,
            numericCount = numericCount,
            textCount = textCount,
            aliasHits = countHeaderAliasHits(row)
        )
    }
}

private fun sharedColumnCount(first: RowProfile, second: RowProfile): Int {
    return first.nonBlankColumns.intersect(second.nonBlankColumns).size
}

private fun detectHeader(rows: List<List<String>>, profiles: List<RowProfile>): HeaderDetection {
    if (rows.isEmpty()) {
        return HeaderDetection(
            dataRowIdx = -1,
            hasHeader = false,
            headerRows = emptyList(),
            headerMode = "generated-no-data"
        )
    }

    var candidateIdx = -1
    val lastIndex = rows.lastIndex
    for (idx in rows.indices) {
        val current = profiles[idx]
        if (!current.looksDataLike) continue

        val repeatedPatternMatches = (idx + 1..minOf(idx + 3, lastIndex)).count { nextIdx ->
            val next = profiles[nextIdx]
            val minOverlap = minOf(
                3,
                minOf(current.nonBlankCount, next.nonBlankCount)
            )
            next.looksDataLike && sharedColumnCount(current, next) >= minOverlap
        }
        val previousAliasHits = profiles.getOrNull(idx - 1)?.aliasHits ?: 0
        val futureSupportsTable = repeatedPatternMatches >= 1
        val immediateHeaderSupportsTable = previousAliasHits >= LEGACY_HEADER_ALIAS_FAST_PATH
        val startsWithDenseData = idx == 0 && (profiles.getOrNull(1)?.looksDataLike == true)
        if (futureSupportsTable || immediateHeaderSupportsTable || startsWithDenseData) {
            candidateIdx = idx
            break
        }
    }

    if (candidateIdx < 0) {
        candidateIdx = profiles.indexOfFirst { it.looksDataLike }
    }
    if (candidateIdx <= 0) {
        return HeaderDetection(
            dataRowIdx = candidateIdx,
            hasHeader = false,
            headerRows = emptyList(),
            headerMode = if (candidateIdx >= 0) "generated-no-header" else "generated-fallback"
        )
    }

    val immediateHeaderIdx = candidateIdx - 1
    val immediateAliasHits = profiles[immediateHeaderIdx].aliasHits
    if (immediateAliasHits >= LEGACY_HEADER_ALIAS_FAST_PATH) {
        return HeaderDetection(
            dataRowIdx = candidateIdx,
            hasHeader = true,
            headerRows = listOf(immediateHeaderIdx),
            headerMode = "legacy-fast-path"
        )
    }

    val lookbackStart = maxOf(0, candidateIdx - MAX_HEADER_LOOKBACK_ROWS)
    val lookbackRows = (lookbackStart until candidateIdx).filter { idx ->
        val profile = profiles[idx]
        profile.nonBlankCount > 0 && !profile.looksDataLike
    }

    val headerRows = when {
        lookbackRows.size >= 2 -> lookbackRows.takeLast(MAX_HEADER_LOOKBACK_ROWS)
        lookbackRows.isNotEmpty() -> lookbackRows
        else -> listOf(immediateHeaderIdx)
    }

    val combinedHeader = mergeHeaderRows(rows, headerRows)
    val combinedAliasHits = countHeaderAliasHits(combinedHeader)
    return when {
        combinedAliasHits > immediateAliasHits && headerRows.size > 1 -> HeaderDetection(
            dataRowIdx = candidateIdx,
            hasHeader = true,
            headerRows = headerRows,
            headerMode = "combined-lookback"
        )
        else -> HeaderDetection(
            dataRowIdx = candidateIdx,
            hasHeader = true,
            headerRows = listOf(immediateHeaderIdx),
            headerMode = "single-row-fallback"
        )
    }
}

private fun mergeHeaderRows(rows: List<List<String>>, headerRows: List<Int>): List<String> {
    if (headerRows.isEmpty()) return emptyList()
    val colCount = headerRows.maxOf { idx -> rows.getOrNull(idx)?.size ?: 0 }
    return (0 until colCount).map { col ->
        headerRows.mapNotNull { rowIdx ->
            rows.getOrNull(rowIdx)?.getOrNull(col)?.takeIf { it.isNotBlank() }
        }.distinct().joinToString(" ")
    }
}

private fun alignTabularWidths(
    header: MutableList<String>,
    headerSource: MutableList<String>,
    dataRows: List<List<String>>
): Triple<MutableList<String>, MutableList<String>, List<List<String>>> {
    val colCount = maxOf(header.size, dataRows.maxOfOrNull { it.size } ?: 0)
    while (header.size < colCount) {
        header.add("")
        headerSource.add("unknown")
    }
    val normalizedRows = dataRows.map { row ->
        if (row.size >= colCount) row else row + List(colCount - row.size) { "" }
    }
    return Triple(header, headerSource, normalizedRows)
}

private fun buildColumnSamples(dataRows: List<List<String>>, colCount: Int): List<ColumnSample> {
    val sampleRows = dataRows.take(MAX_PATTERN_SAMPLE_ROWS)
    return (0 until colCount).map { col ->
        val values = sampleRows.map { row -> row.getOrNull(col)?.trim().orEmpty() }
        val nonBlankValues = values.filter { it.isNotBlank() }
        val numericValues = nonBlankValues.mapNotNull(::parseAnalysisNumber)
        val digitOnlyValues = nonBlankValues.filter { value -> value.all(Char::isDigit) }
        val lengthValues = nonBlankValues.map { it.length }
        ColumnSample(
            columnIndex = col,
            values = values,
            nonBlankValues = nonBlankValues,
            numericValues = numericValues,
            medianNumeric = median(numericValues),
            numericRatio = ratio(numericValues.size, sampleRows.size),
            integerRatio = ratio(
                numericValues.count { value -> value == value.toLong().toDouble() },
                numericValues.size
            ),
            decimalRawRatio = ratio(
                nonBlankValues.count { value ->
                    value.contains(',') || value.contains('.')
                },
                nonBlankValues.size
            ),
            dominantLengthShare = dominantShare(lengthValues),
            dominantValueShare = dominantShare(nonBlankValues),
            digitLongRatio = ratio(
                digitOnlyValues.count { value -> value.length in 8..14 },
                nonBlankValues.size
            ),
            shortCodeRatio = ratio(
                nonBlankValues.count { value ->
                    value.length in 4..12 &&
                        value.any(Char::isDigit) &&
                        (value.length < 8 || value.any(Char::isLetter))
                },
                nonBlankValues.size
            ),
            alphaNumericRatio = ratio(
                nonBlankValues.count { value ->
                    value.any(Char::isLetter) && value.any(Char::isDigit)
                },
                nonBlankValues.size
            ),
            textRatio = ratio(
                nonBlankValues.count { value ->
                    parseAnalysisNumber(value) == null && value.length >= 3
                },
                nonBlankValues.size
            ),
            longTextRatio = ratio(
                nonBlankValues.count { value ->
                    parseAnalysisNumber(value) == null &&
                        (value.length >= 5 || value.contains(' '))
                },
                nonBlankValues.size
            ),
            smallPositiveRatio = ratio(
                numericValues.count { value -> value > 0.0 && value <= 200.0 },
                numericValues.size
            ),
            priceLikeMagnitudeRatio = ratio(
                numericValues.count { value -> value >= 20.0 },
                numericValues.size
            )
        )
    }
}

private fun scorePatternCandidates(
    field: String,
    availableColumns: List<Int>,
    columnSamples: List<ColumnSample>,
    numericMedianRank: Map<Int, Double>,
    dataRows: List<List<String>>
): List<ExcelColumnCandidate> {
    val minEvidence = minimumEvidenceFor(dataRows.size)
    return availableColumns.map { col ->
        val sample = columnSamples[col]
        val reasons = mutableListOf<String>()
        val score = when (field) {
            "barcode" -> {
                if (sample.nonBlankValues.size < minEvidence) {
                    reasons += "insufficient-evidence"
                    0.0
                } else {
                    reasons += "digits=${"%.2f".format(sample.digitLongRatio)}"
                    reasons += "len=${"%.2f".format(sample.dominantLengthShare)}"
                    (sample.numericRatio * 0.20) +
                        (sample.digitLongRatio * 0.55) +
                        (sample.dominantLengthShare * 0.15) +
                        ((1.0 - sample.alphaNumericRatio) * 0.10)
                }
            }
            "itemNumber" -> {
                if (sample.nonBlankValues.size < minEvidence) {
                    reasons += "insufficient-evidence"
                    0.0
                } else {
                    reasons += "short=${"%.2f".format(sample.shortCodeRatio)}"
                    reasons += "alphaNum=${"%.2f".format(sample.alphaNumericRatio)}"
                    (sample.numericRatio * 0.15) +
                        (sample.shortCodeRatio * 0.45) +
                        (sample.alphaNumericRatio * 0.20) +
                        ((1.0 - sample.digitLongRatio) * 0.20)
                }
            }
            "productName" -> {
                if (sample.nonBlankValues.size < minEvidence) {
                    reasons += "insufficient-evidence"
                    0.0
                } else {
                    reasons += "text=${"%.2f".format(sample.textRatio)}"
                    reasons += "long=${"%.2f".format(sample.longTextRatio)}"
                    (sample.textRatio * 0.55) +
                        (sample.longTextRatio * 0.30) +
                        ((1.0 - sample.numericRatio) * 0.15)
                }
            }
            "quantity" -> {
                if (sample.numericValues.size < minEvidence) {
                    reasons += "insufficient-evidence"
                    0.0
                } else {
                    val rank = numericMedianRank[sample.columnIndex] ?: 0.5
                    val rowNumberLike = rowNumberLikeRatio(sample)
                    reasons += "small=${"%.2f".format(sample.smallPositiveRatio)}"
                    reasons += "rank=${"%.2f".format(rank)}"
                    reasons += "seq=${"%.2f".format(rowNumberLike)}"
                    (sample.numericRatio * 0.30) +
                        (sample.integerRatio * 0.20) +
                        (sample.smallPositiveRatio * 0.25) +
                        ((1.0 - rank) * 0.15) +
                        (sample.dominantValueShare * 0.10) -
                        (rowNumberLike * 0.25)
                }
            }
            "purchasePrice" -> {
                if (sample.numericValues.size < minEvidence) {
                    reasons += "insufficient-evidence"
                    0.0
                } else {
                    val rank = numericMedianRank[sample.columnIndex] ?: 0.5
                    reasons += "price=${"%.2f".format(sample.priceLikeMagnitudeRatio)}"
                    reasons += "rank=${"%.2f".format(rank)}"
                    (sample.numericRatio * 0.30) +
                        (sample.priceLikeMagnitudeRatio * 0.20) +
                        (rank * 0.20) +
                        (sample.decimalRawRatio * 0.10) +
                        ((1.0 - sample.digitLongRatio) * 0.05) +
                        ((1.0 - sample.dominantValueShare) * 0.05) +
                        ((1.0 - sample.shortCodeRatio) * 0.10)
                }
            }
            "totalPrice" -> {
                val quantityCol = availableColumns.firstOrNull()
                val purchaseCol = availableColumns.getOrNull(1)
                if (quantityCol == null || purchaseCol == null) {
                    reasons += "missing-dependencies"
                    0.0
                } else {
                    val matches = dataRows.count { row ->
                        val quantity = parseAnalysisNumber(row.getOrNull(quantityCol))
                        val purchase = parseAnalysisNumber(row.getOrNull(purchaseCol))
                        val total = parseAnalysisNumber(row.getOrNull(col))
                        if (quantity == null || purchase == null || total == null) return@count false
                        val expected = quantity * purchase
                        val epsilon = 0.10 * expected.coerceAtLeast(1.0)
                        kotlin.math.abs(total - expected) <= epsilon
                    }
                    reasons += "mul=${"%.2f".format(ratio(matches, dataRows.size))}"
                    ratio(matches, dataRows.size)
                }
            }
            else -> 0.0
        }
        ExcelColumnCandidate(
            columnIndex = col,
            score = score.coerceIn(0.0, 1.0),
            reasons = reasons
        )
    }.sortedWith(
        compareByDescending<ExcelColumnCandidate> { it.score }
            .thenBy { it.columnIndex }
    )
}

private fun confidenceFor(
    selected: ExcelColumnCandidate,
    runnerUp: ExcelColumnCandidate?
): String {
    return when {
        selected.score < MIN_PATTERN_SCORE -> "low"
        runnerUp == null -> "high"
        selected.score - runnerUp.score <= AMBIGUITY_MARGIN -> "low"
        selected.score >= 0.70 -> "high"
        else -> "medium"
    }
}

private fun shouldAssignCandidate(
    selected: ExcelColumnCandidate,
    runnerUp: ExcelColumnCandidate?
): Boolean {
    if (selected.score < MIN_PATTERN_SCORE) return false
    if (runnerUp == null) return true
    return selected.score - runnerUp.score > AMBIGUITY_MARGIN
}

private fun withPatternHeaderSource(
    headerSource: MutableList<String>,
    col: Int
) {
    if (headerSource.getOrNull(col) != "alias") {
        headerSource[col] = "pattern"
    }
}

private fun synthesizeFieldTraces(
    fields: List<String>,
    decisions: Map<String, ExcelFieldDecisionTrace>
): List<ExcelFieldDecisionTrace> {
    return fields.map { field ->
        decisions[field] ?: ExcelFieldDecisionTrace(
            field = field,
            selectedColumnIndex = null,
            confidence = "low",
            reason = "not-evaluated",
            candidates = emptyList()
        )
    }
}

private fun detectPurchaseTotalPair(
    quantityCol: Int,
    availableColumns: List<Int>,
    columnSamples: List<ColumnSample>,
    dataRows: List<List<String>>
): NumericPairDetection? {
    val minEvidence = minimumEvidenceFor(dataRows.size)
    var best: NumericPairDetection? = null

    availableColumns.forEachIndexed { index, firstCol ->
        val firstMedian = columnSamples[firstCol].medianNumeric ?: return@forEachIndexed
        for (secondCol in availableColumns.drop(index + 1)) {
            val secondMedian = columnSamples[secondCol].medianNumeric ?: continue
            val purchaseCol = if (firstMedian <= secondMedian) firstCol else secondCol
            val totalCol = if (purchaseCol == firstCol) secondCol else firstCol
            var informativeRows = 0
            var matches = 0
            var errorSum = 0.0
            dataRows.forEach { row ->
                val quantity = parseAnalysisNumber(row.getOrNull(quantityCol))
                val purchase = parseAnalysisNumber(row.getOrNull(purchaseCol))
                val total = parseAnalysisNumber(row.getOrNull(totalCol))
                if (quantity == null || purchase == null || total == null) return@forEach
                informativeRows += 1
                val expected = quantity * purchase
                errorSum += kotlin.math.abs(total - expected) / expected.coerceAtLeast(1.0)
                val epsilon = 0.10 * expected.coerceAtLeast(1.0)
                if (kotlin.math.abs(total - expected) <= epsilon) {
                    matches += 1
                }
            }
            val matchRatio = ratio(matches, informativeRows)
            val averageRelativeError = if (informativeRows == 0) {
                Double.POSITIVE_INFINITY
            } else {
                errorSum / informativeRows.toDouble()
            }
            val meetsEvidence = informativeRows >= minEvidence &&
                (informativeRows > 1 || matchRatio >= 1.0)
            val current = NumericPairDetection(
                purchaseColumn = purchaseCol,
                totalColumn = totalCol,
                matchRatio = matchRatio,
                averageRelativeError = averageRelativeError
            )
            val bestPair = best
            val isBetter = when {
                bestPair == null -> true
                current.matchRatio > bestPair.matchRatio -> true
                current.matchRatio < bestPair.matchRatio -> false
                current.averageRelativeError < bestPair.averageRelativeError -> true
                current.averageRelativeError > bestPair.averageRelativeError -> false
                else -> current.purchaseColumn < bestPair.purchaseColumn
            }
            if (meetsEvidence && isBetter) {
                best = NumericPairDetection(
                    purchaseColumn = purchaseCol,
                    totalColumn = totalCol,
                    matchRatio = matchRatio,
                    averageRelativeError = averageRelativeError
                )
            }
        }
    }

    return best?.takeIf { detection ->
        detection.matchRatio >= if (dataRows.size <= 1) 1.0 else 0.70
    }
}

private fun rowNumberLikeRatio(sample: ColumnSample): Double {
    var informative = 0
    var matches = 0
    sample.values.forEachIndexed { index, value ->
        val number = parseAnalysisNumber(value) ?: return@forEachIndexed
        if (number != number.toLong().toDouble()) return@forEachIndexed
        informative += 1
        if (number.toLong() == (index + 1).toLong()) {
            matches += 1
        }
    }
    return ratio(matches, informative)
}

private fun shouldSkipHeaderAlias(
    key: String,
    rawHeader: String,
    sample: ColumnSample
): Boolean {
    if (key != "itemNumber") return false
    if (normalizeHeader(rawHeader) != normalizeHeader("ref.cajas")) return false
    return rowNumberLikeRatio(sample) >= MIN_ROW_NUMBER_LIKE_RATIO
}

private fun analyzeRows(
    context: Context,
    rows: List<List<String>>
): Triple<List<String>, List<List<String>>, List<String>> {
    val result = analyzeRowsDetailed(context, rows)
    return Triple(result.header, result.dataRows, result.headerSource)
}

internal fun analyzeRowsDetailed(
    context: Context,
    rows: List<List<String>>
): ExcelAnalysisResult {
    val profiles = buildRowProfiles(rows)
    val headerDetection = detectHeader(rows, profiles)
    val hasHeader = headerDetection.hasHeader

    var header: MutableList<String>
    var headerSource: MutableList<String>
    var dataRows: List<List<String>>

    if (hasHeader) {
        header = mergeHeaderRows(rows, headerDetection.headerRows).toMutableList()
        headerSource = MutableList(header.size) { "unknown" }
        val start = headerDetection.dataRowIdx.coerceAtLeast(0)
        dataRows = rows.drop(start).filter { row ->
            val numericCount = row.count { parseAnalysisNumber(it) != null }
            val textCount = row.count { it.isNotBlank() && parseAnalysisNumber(it) == null }
            val nonBlankCount = row.count { it.isNotBlank() }
            nonBlankCount >= 4 && numericCount >= 2 && textCount >= 1
        }
    } else {
        val colCount = rows.maxOfOrNull { it.size } ?: 0
        header = (1..colCount).map {
            "${context.getString(R.string.generated_column_prefix)} $it"
        }.toMutableList()
        headerSource = MutableList(header.size) { "generated" }
        dataRows = rows
    }
    if (dataRows.isEmpty()) {
        return ExcelAnalysisResult(
            header = header,
            dataRows = dataRows,
            headerSource = headerSource,
            trace = ExcelAnalysisTrace(
                hasHeader = hasHeader,
                dataRowIdx = headerDetection.dataRowIdx,
                headerRows = headerDetection.headerRows,
                headerMode = headerDetection.headerMode,
                sampleSize = 0,
                fieldDecisions = emptyList()
            )
        )
    }

    val aligned = alignTabularWidths(header, headerSource, dataRows)
    header = aligned.first
    headerSource = aligned.second
    dataRows = aligned.third

    val possibleNames = KNOWN_EXCEL_HEADER_ALIASES
    val headerMap = mutableMapOf<String, Int>()
    val usedCols = mutableSetOf<Int>()
    val decisionTraces = mutableMapOf<String, ExcelFieldDecisionTrace>()

    val prunedColumns = pruneTotallyEmptyColumns(header, headerSource, dataRows)
    header = prunedColumns.header
    headerSource = prunedColumns.headerSource
    dataRows = prunedColumns.dataRows

    val colCount = header.size
    val threshold = (dataRows.size * 0.5).toInt().coerceAtLeast(1)
    val rawHeader = header.toList()

    val columnSamples = buildColumnSamples(dataRows, colCount)
    if (hasHeader) {
        val prioritizedKeys = listOf("retailPrice", "purchasePrice") +
            possibleNames.keys.filterNot { it == "retailPrice" || it == "purchasePrice" }

        for (key in prioritizedKeys) {
            val aliases = possibleNames[key] ?: continue
            val foundIdx = rawHeader.indexOfFirst { colName ->
                if (normalizeHeader(colName).isBlank()) false else {
                    val normCol = normalizeHeader(colName)
                    aliases.any { alias -> normCol == normalizeHeader(alias) }
                }
            }
            if (foundIdx >= 0 && !usedCols.contains(foundIdx)) {
                if (shouldSkipHeaderAlias(key, rawHeader[foundIdx], columnSamples[foundIdx])) {
                    decisionTraces[key] = ExcelFieldDecisionTrace(
                        field = key,
                        selectedColumnIndex = null,
                        confidence = "low",
                        reason = "header-alias-rejected",
                        candidates = listOf(
                            ExcelColumnCandidate(
                                columnIndex = foundIdx,
                                score = 0.0,
                                reasons = listOf("row-number-like-ref-cajas")
                            )
                        )
                    )
                    continue
                }
                headerMap[key] = foundIdx
                usedCols.add(foundIdx)
                header[foundIdx] = key
                headerSource[foundIdx] = "alias"
                decisionTraces[key] = ExcelFieldDecisionTrace(
                    field = key,
                    selectedColumnIndex = foundIdx,
                    confidence = "high",
                    reason = "header-alias",
                    candidates = listOf(
                        ExcelColumnCandidate(
                            columnIndex = foundIdx,
                            score = 1.0,
                            reasons = listOf("alias-match")
                        )
                    )
                )
            }
        }
    }
    val minEvidence = minimumEvidenceFor(dataRows.size)
    val rankedNumericColumns = columnSamples
        .filter { it.numericValues.size >= minEvidence && it.digitLongRatio < 0.90 }
        .sortedBy { it.medianNumeric ?: Double.MAX_VALUE }
    val numericMedianRank = rankedNumericColumns
        .withIndex()
        .associate { (index, sample) ->
            sample.columnIndex to if (rankedNumericColumns.size <= 1) {
                0.5
            } else {
                ratio(index, rankedNumericColumns.size - 1)
            }
        }

    fun setPatternField(key: String, col: Int, trace: ExcelFieldDecisionTrace) {
        if (usedCols.contains(col)) return
        headerMap[key] = col
        usedCols.add(col)
        header[col] = key
        withPatternHeaderSource(headerSource, col)
        decisionTraces[key] = trace.copy(selectedColumnIndex = col)
    }

    val firstPassFields = listOf("barcode", "productName", "quantity")

    val pendingFieldCandidates = firstPassFields
        .filterNot(headerMap::containsKey)
        .associateWith { field ->
            scorePatternCandidates(
                field = field,
                availableColumns = (0 until colCount).filterNot(usedCols::contains),
                columnSamples = columnSamples,
                numericMedianRank = numericMedianRank,
                dataRows = dataRows
            )
        }

    val greedyFieldOrder = pendingFieldCandidates
        .entries
        .sortedWith(
            compareByDescending<Map.Entry<String, List<ExcelColumnCandidate>>> {
                it.value.firstOrNull()?.score ?: 0.0
            }.thenBy { firstPassFields.indexOf(it.key) }
        )
        .map { it.key }

    for (field in greedyFieldOrder) {
        val ranked = pendingFieldCandidates[field].orEmpty()
        val availableRanked = ranked.filterNot { usedCols.contains(it.columnIndex) }
        val selected = availableRanked.firstOrNull()
        val runnerUp = availableRanked.getOrNull(1)
        if (selected != null) {
            val confidence = confidenceFor(selected, runnerUp)
            val reason = if (shouldAssignCandidate(selected, runnerUp)) {
                "pattern-score"
            } else {
                "low-confidence"
            }
            val trace = ExcelFieldDecisionTrace(
                field = field,
                selectedColumnIndex = if (shouldAssignCandidate(selected, runnerUp)) {
                    selected.columnIndex
                } else {
                    null
                },
                confidence = confidence,
                reason = reason,
                candidates = availableRanked.take(3)
            )
            decisionTraces[field] = trace
            if (shouldAssignCandidate(selected, runnerUp)) {
                setPatternField(field, selected.columnIndex, trace)
            }
        } else {
            decisionTraces[field] = ExcelFieldDecisionTrace(
                field = field,
                selectedColumnIndex = null,
                confidence = "low",
                reason = "no-candidate",
                candidates = emptyList()
            )
        }
    }

    if (!headerMap.containsKey("purchasePrice") || !headerMap.containsKey("totalPrice")) {
        val quantityIdx = headerMap["quantity"]
        if (quantityIdx != null) {
            val pair = detectPurchaseTotalPair(
                quantityCol = quantityIdx,
                availableColumns = (0 until colCount).filterNot(usedCols::contains),
                columnSamples = columnSamples,
                dataRows = dataRows
            )
            if (pair != null) {
                if (!headerMap.containsKey("purchasePrice")) {
                    val purchaseTrace = ExcelFieldDecisionTrace(
                        field = "purchasePrice",
                        selectedColumnIndex = pair.purchaseColumn,
                        confidence = "high",
                        reason = "quantity-multiplication",
                        candidates = listOf(
                            ExcelColumnCandidate(
                                columnIndex = pair.purchaseColumn,
                                score = pair.matchRatio,
                                reasons = listOf("pair=${"%.2f".format(pair.matchRatio)}")
                            )
                        )
                    )
                    setPatternField("purchasePrice", pair.purchaseColumn, purchaseTrace)
                }
                if (!headerMap.containsKey("totalPrice")) {
                    val totalTrace = ExcelFieldDecisionTrace(
                        field = "totalPrice",
                        selectedColumnIndex = pair.totalColumn,
                        confidence = "high",
                        reason = "quantity-multiplication",
                        candidates = listOf(
                            ExcelColumnCandidate(
                                columnIndex = pair.totalColumn,
                                score = pair.matchRatio,
                                reasons = listOf("pair=${"%.2f".format(pair.matchRatio)}")
                            )
                        )
                    )
                    setPatternField("totalPrice", pair.totalColumn, totalTrace)
                }
            }
        }
    }

    val secondPassFields = listOf("itemNumber", "purchasePrice")
    secondPassFields
        .filterNot(headerMap::containsKey)
        .forEach { field ->
            val availableRanked = scorePatternCandidates(
                field = field,
                availableColumns = (0 until colCount).filterNot(usedCols::contains),
                columnSamples = columnSamples,
                numericMedianRank = numericMedianRank,
                dataRows = dataRows
            )
            val selected = availableRanked.firstOrNull()
            val runnerUp = availableRanked.getOrNull(1)
            if (selected != null) {
                val assign = shouldAssignCandidate(selected, runnerUp)
                val trace = ExcelFieldDecisionTrace(
                    field = field,
                    selectedColumnIndex = if (assign) selected.columnIndex else null,
                    confidence = confidenceFor(selected, runnerUp),
                    reason = if (assign) "pattern-score" else "low-confidence",
                    candidates = availableRanked.take(3)
                )
                decisionTraces[field] = trace
                if (assign) {
                    setPatternField(field, selected.columnIndex, trace)
                }
            }
        }

    if (!headerMap.containsKey("totalPrice")) {
        val quantityIdx = headerMap["quantity"]
        val purchaseIdx = headerMap["purchasePrice"]
        val ranked = if (quantityIdx != null && purchaseIdx != null) {
            scorePatternCandidates(
                field = "totalPrice",
                availableColumns = (0 until colCount)
                    .filterNot { usedCols.contains(it) }
                    .let { listOf(quantityIdx, purchaseIdx) + it },
                columnSamples = columnSamples,
                numericMedianRank = numericMedianRank,
                dataRows = dataRows
            ).filterNot { it.columnIndex == quantityIdx || it.columnIndex == purchaseIdx }
        } else {
            emptyList()
        }
        val selected = ranked.firstOrNull()
        val runnerUp = ranked.getOrNull(1)
        if (selected != null) {
            val confidence = confidenceFor(selected, runnerUp)
            val trace = ExcelFieldDecisionTrace(
                field = "totalPrice",
                selectedColumnIndex = if (shouldAssignCandidate(selected, runnerUp)) {
                    selected.columnIndex
                } else {
                    null
                },
                confidence = confidence,
                reason = if (shouldAssignCandidate(selected, runnerUp)) {
                    "total-multiplication"
                } else {
                    "low-confidence"
                },
                candidates = ranked.take(3)
            )
            decisionTraces["totalPrice"] = trace
            if (shouldAssignCandidate(selected, runnerUp)) {
                setPatternField("totalPrice", selected.columnIndex, trace)
            }
        }
    }

    if (!hasHeader) {
        val supplementari = listOf(
            "retailPrice",
            "secondProductName",
            "supplier",
            "discount",
            "discountedPrice",
            "rowNumber"
        )
        for (key in supplementari) {
            if (!headerMap.containsKey(key)) {
                when (key) {
                    "retailPrice", "discountedPrice" -> {
                        for (col in 0 until colCount) {
                            if (usedCols.contains(col)) continue
                            val sample = columnSamples[col]
                            val nums = dataRows.mapNotNull { row ->
                                parseAnalysisNumber(row.getOrNull(col))
                            }
                            if (
                                nums.isNotEmpty() &&
                                nums.all { it > 0 } &&
                                nums.size >= dataRows.size * 0.7 &&
                                sample.shortCodeRatio < 0.5
                            ) {
                                headerMap[key] = col
                                usedCols.add(col)
                                header[col] = key
                                withPatternHeaderSource(headerSource, col)
                                break
                            }
                        }
                    }
                    "secondProductName", "supplier" -> {
                        for (col in 0 until colCount) {
                            if (usedCols.contains(col)) continue
                            val matches = dataRows.count { row ->
                                val v = row.getOrNull(col)?.trim() ?: ""
                                v.length >= 3 && parseAnalysisNumber(v) == null
                            }
                            if (matches >= dataRows.size * 0.5) {
                                headerMap[key] = col
                                usedCols.add(col)
                                header[col] = key
                                withPatternHeaderSource(headerSource, col)
                                break
                            }
                        }
                    }
                    "discount" -> {
                        for (col in 0 until colCount) {
                            if (usedCols.contains(col)) continue
                            val matches = dataRows.count { row ->
                                val v = row.getOrNull(col)?.trim() ?: ""
                                v.matches(Regex("""^(0[.,]\d{1,2})$""")) ||
                                    v.matches(Regex("""^\d{1,2}%$"""))
                            }
                            if (matches >= threshold) {
                                headerMap[key] = col
                                usedCols.add(col)
                                header[col] = key
                                withPatternHeaderSource(headerSource, col)
                                break
                            }
                        }
                    }
                    "rowNumber" -> {
                        for (col in 0 until colCount) {
                            if (usedCols.contains(col)) continue
                            val matches = dataRows.count { row ->
                                val v = row.getOrNull(col)?.trim() ?: ""
                                v.matches(Regex("""^\d+$""")) && v.length <= 6
                            }
                            if (matches >= threshold) {
                                headerMap[key] = col
                                usedCols.add(col)
                                header[col] = key
                                withPatternHeaderSource(headerSource, col)
                                break
                            }
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

    fun normalizeSummaryLabel(value: String): String {
        return value.trim()
            .lowercase()
            .replace(Regex("[\\s:：()（）._-]"), "")
    }

    val summaryTokens = listOf(
        "合计", "总计", "小计", "汇总", "合計", "總計", "小計", "總結",
        "总额", "总数", "总价", "总数量", "总金额", "总件数",
        "subtotal", "total", "totale", "tot.", "sommario", "resumen", "sum"
    ).map(::normalizeSummaryLabel)

    val summarySuffixTokens = setOf(
        "",
        "总数", "总价", "总数量", "总金额", "总件数",
        "quantity", "qty", "count", "price", "amount", "importe"
    ).map(::normalizeSummaryLabel).toSet()

    fun isSummaryLabel(value: String): Boolean {
        val normalized = normalizeSummaryLabel(value)
        if (normalized.isBlank()) return false
        if (normalized in summaryTokens) return true
        return summaryTokens.any { token ->
            normalized.startsWith(token) &&
                normalizeSummaryLabel(normalized.removePrefix(token)) in summarySuffixTokens
        }
    }

    fun hasPlausibleItemIdentity(item: String): Boolean {
        val trimmedItem = item.trim()
        if (trimmedItem.isBlank() || isSummaryLabel(trimmedItem)) return false
        val digitCount = trimmedItem.count(Char::isDigit)
        val letterCount = trimmedItem.count(Char::isLetter)
        val separatorCount = trimmedItem.count { it == '-' || it == '/' || it == '_' }
        return when {
            digitCount == trimmedItem.length -> digitCount >= 4
            digitCount > 0 -> true
            separatorCount > 0 -> true
            letterCount == trimmedItem.length -> trimmedItem.length >= 5
            else -> trimmedItem.length >= 4
        }
    }

    fun hasPlausibleProductIdentity(code: String, item: String, name: String, secondName: String): Boolean {
        val barcodeDigits = code.filter(Char::isDigit)
        val trimmedName = name.trim()
        val trimmedSecondName = secondName.trim()
        val barcodeLooksPlausible = barcodeDigits.length >= 8
        val itemLooksPlausible = hasPlausibleItemIdentity(item)
        val nameLooksPlausible = trimmedName.length >= 3 && !isSummaryLabel(trimmedName)
        val secondNameLooksPlausible = trimmedSecondName.length >= 3 && !isSummaryLabel(trimmedSecondName)
        return barcodeLooksPlausible || itemLooksPlausible || nameLooksPlausible || secondNameLooksPlausible
    }

    fun hasShiftedAggregatePattern(
        code: String,
        item: String,
        name: String,
        secondName: String,
        quantity: String,
        purchase: String,
        total: String,
        retail: String,
        discounted: String,
        realQuantity: String
    ): Boolean {
        val numericIdentityCount = listOf(code, item, name, secondName)
            .count { parseAnalysisNumber(it) != null }
        val numericMeasureCount = listOf(quantity, purchase, total, retail, discounted, realQuantity)
            .count { parseAnalysisNumber(it) != null }
        return numericIdentityCount >= 1 && numericMeasureCount == 0
    }

    fun isSummaryRow(row: List<String>): Boolean {
        fun valAt(key: String) = row.getOrNull(headerMap[key] ?: -1)?.trim().orEmpty()
        val name = valAt("productName")
        val secondName = valAt("secondProductName")
        val item = valAt("itemNumber")
        val code = valAt("barcode")
        val quantity = valAt("quantity")
        val purchase = valAt("purchasePrice")
        val total = valAt("totalPrice")
        val retail = valAt("retailPrice")
        val discounted = valAt("discountedPrice")
        val realQuantity = valAt("realQuantity")
        val firstText = row.firstOrNull { it.isNotBlank() && parseAnalysisNumber(it) == null }
            ?.trim().orEmpty()
        val looksLikeToken = isSummaryLabel(firstText) || isSummaryLabel(name)
        val manyNumbers = row.count { parseAnalysisNumber(it) != null } >= 2
        val lacksPlausibleIdentity = !hasPlausibleProductIdentity(code, item, name, secondName)
        val shiftedAggregates = hasShiftedAggregatePattern(
            code = code,
            item = item,
            name = name,
            secondName = secondName,
            quantity = quantity,
            purchase = purchase,
            total = total,
            retail = retail,
            discounted = discounted,
            realQuantity = realQuantity
        )
        return looksLikeToken && manyNumbers && (lacksPlausibleIdentity || shiftedAggregates)
    }

    dataRows = dataRows.filterNot { isSummaryRow(it) }

    return ExcelAnalysisResult(
        header = header,
        dataRows = dataRows,
        headerSource = headerSource,
        trace = ExcelAnalysisTrace(
            hasHeader = hasHeader,
            dataRowIdx = headerDetection.dataRowIdx,
            headerRows = headerDetection.headerRows,
            headerMode = headerDetection.headerMode,
            sampleSize = dataRows.take(MAX_PATTERN_SAMPLE_ROWS).size,
            fieldDecisions = synthesizeFieldTraces(
                fields = listOf(
                    "itemNumber",
                    "barcode",
                    "productName",
                    "quantity",
                    "purchasePrice",
                    "totalPrice"
                ),
                decisions = decisionTraces
            )
        )
    )
}

fun analyzePoiSheet(context: Context, sheet: Sheet)
        : Triple<List<String>, List<List<String>>, List<String>> {
    val result = analyzePoiSheetDetailed(context, sheet)
    return Triple(result.header, result.dataRows, result.headerSource)
}

internal fun analyzePoiSheetDetailed(
    context: Context,
    sheet: Sheet
): ExcelAnalysisResult {
    val fmt = DataFormatter()
    val rows = mutableListOf<List<String>>()
    for (r in 0..sheet.lastRowNum) {
        val row = sheet.getRow(r) ?: continue
        val last = row.lastCellNum.toInt().coerceAtLeast(0)
        rows += (0 until last).map { c -> fmt.formatCellValue(row.getCell(c)).trim() }
    }
    return analyzeRowsDetailed(context, normalizeTabularRows(rows))
}
