package com.example.merchandisecontrolsplitview.util

import android.content.Context
import androidx.annotation.StringRes
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.Category
import com.example.merchandisecontrolsplitview.data.PriceHistoryExportRow
import com.example.merchandisecontrolsplitview.data.ProductWithDetails
import com.example.merchandisecontrolsplitview.data.Supplier
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.streaming.SXSSFWorkbook

enum class DatabaseExportSheet(
    val technicalName: String,
    val sigil: String,
    val weight: Int,
    @param:StringRes val labelRes: Int
) {
    PRODUCTS(
        technicalName = DatabaseExportConstants.SHEET_PRODUCTS,
        sigil = "P",
        weight = 35,
        labelRes = R.string.export_sheet_products
    ),
    SUPPLIERS(
        technicalName = DatabaseExportConstants.SHEET_SUPPLIERS,
        sigil = "S",
        weight = 10,
        labelRes = R.string.export_sheet_suppliers
    ),
    CATEGORIES(
        technicalName = DatabaseExportConstants.SHEET_CATEGORIES,
        sigil = "C",
        weight = 10,
        labelRes = R.string.export_sheet_categories
    ),
    PRICE_HISTORY(
        technicalName = DatabaseExportConstants.SHEET_PRICE_HISTORY,
        sigil = "PH",
        weight = 45,
        labelRes = R.string.export_sheet_price_history
    )
}

data class ExportSheetSelection(
    val products: Boolean = true,
    val suppliers: Boolean = true,
    val categories: Boolean = true,
    val priceHistory: Boolean = true
) {
    val isEmpty: Boolean
        get() = !products && !suppliers && !categories && !priceHistory

    val isFullExport: Boolean
        get() = products && suppliers && categories && priceHistory

    fun selectedSheetsInOrder(): List<DatabaseExportSheet> = buildList {
        if (products) add(DatabaseExportSheet.PRODUCTS)
        if (suppliers) add(DatabaseExportSheet.SUPPLIERS)
        if (categories) add(DatabaseExportSheet.CATEGORIES)
        if (priceHistory) add(DatabaseExportSheet.PRICE_HISTORY)
    }

    fun partialSigilsInSheetOrder(): List<String> =
        selectedSheetsInOrder().map(DatabaseExportSheet::sigil)

    fun withSheet(sheet: DatabaseExportSheet, selected: Boolean): ExportSheetSelection =
        when (sheet) {
            DatabaseExportSheet.PRODUCTS -> copy(products = selected)
            DatabaseExportSheet.SUPPLIERS -> copy(suppliers = selected)
            DatabaseExportSheet.CATEGORIES -> copy(categories = selected)
            DatabaseExportSheet.PRICE_HISTORY -> copy(priceHistory = selected)
        }

    companion object {
        fun full(): ExportSheetSelection = ExportSheetSelection()
        fun productsOnly(): ExportSheetSelection = ExportSheetSelection(
            products = true,
            suppliers = false,
            categories = false,
            priceHistory = false
        )
        fun catalogOnly(): ExportSheetSelection = ExportSheetSelection(
            products = false,
            suppliers = true,
            categories = true,
            priceHistory = false
        )
        fun priceHistoryOnly(): ExportSheetSelection = ExportSheetSelection(
            products = false,
            suppliers = false,
            categories = false,
            priceHistory = true
        )
    }
}

data class DatabaseExportSchema(
    val productHeaders: List<String>,
    val supplierHeaders: List<String> = DatabaseExportConstants.SUPPLIER_HEADERS,
    val categoryHeaders: List<String> = DatabaseExportConstants.CATEGORY_HEADERS,
    val priceHistoryHeaders: List<String> = DatabaseExportConstants.PRICE_HISTORY_HEADERS
)

data class DatabaseExportContent(
    val products: List<ProductWithDetails> = emptyList(),
    val suppliers: List<Supplier> = emptyList(),
    val categories: List<Category> = emptyList(),
    val priceHistoryRows: List<PriceHistoryExportRow> = emptyList()
)

object DatabaseExportConstants {
    const val SHEET_PRODUCTS = "Products"
    const val SHEET_SUPPLIERS = "Suppliers"
    const val SHEET_CATEGORIES = "Categories"
    const val SHEET_PRICE_HISTORY = "PriceHistory"

    const val PARTIAL_FILENAME_PREFIX = "Database_partial_"
    const val FULL_FILENAME_PREFIX = "Database_"
    const val FILENAME_EXTENSION = ".xlsx"
    val FILENAME_TIMESTAMP_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy_MM_dd_HH-mm-ss")

    val PRODUCT_HEADER_RES_IDS = listOf(
        R.string.header_barcode,
        R.string.header_item_number,
        R.string.header_product_name,
        R.string.header_second_product_name,
        R.string.header_purchase_price,
        R.string.header_retail_price,
        R.string.product_purchase_price_old_short,
        R.string.product_retail_price_old_short,
        R.string.header_supplier,
        R.string.header_category,
        R.string.header_stock_quantity
    )

    val SUPPLIER_HEADERS = listOf("id", "name")
    val CATEGORY_HEADERS = listOf("id", "name")
    val PRICE_HISTORY_HEADERS = listOf(
        "productBarcode",
        "timestamp",
        "type",
        "oldPrice",
        "newPrice",
        "source"
    )

    const val PRICE_TYPE_PURCHASE = "purchase"
    const val PRICE_TYPE_RETAIL = "retail"

    /** Dimensione pagina repository → export (prodotti e price history). */
    const val DATABASE_EXPORT_PAGE_SIZE = 500
}

fun buildDatabaseExportSchema(context: Context): DatabaseExportSchema =
    DatabaseExportSchema(
        productHeaders = DatabaseExportConstants.PRODUCT_HEADER_RES_IDS.map(context::getString)
    )

fun buildDatabaseExportDisplayName(
    selection: ExportSheetSelection,
    timestamp: LocalDateTime = LocalDateTime.now()
): String {
    val formattedTimestamp =
        DatabaseExportConstants.FILENAME_TIMESTAMP_FORMATTER.format(timestamp)

    return if (selection.isFullExport) {
        DatabaseExportConstants.FULL_FILENAME_PREFIX +
            formattedTimestamp +
            DatabaseExportConstants.FILENAME_EXTENSION
    } else {
        DatabaseExportConstants.PARTIAL_FILENAME_PREFIX +
            selection.partialSigilsInSheetOrder().joinToString(separator = "_") +
            "_" +
            formattedTimestamp +
            DatabaseExportConstants.FILENAME_EXTENSION
    }
}

@Suppress("DEPRECATION")
fun writeDatabaseExport(
    outputStream: OutputStream,
    selection: ExportSheetSelection,
    schema: DatabaseExportSchema,
    content: DatabaseExportContent,
    onSheetWritten: ((DatabaseExportSheet) -> Unit)? = null
) {
    require(!selection.isEmpty) { "At least one sheet must be selected." }

    val workbook = SXSSFWorkbook(100)
    workbook.isCompressTempFiles = true

    try {
        selection.selectedSheetsInOrder().forEach { sheet ->
            when (sheet) {
                DatabaseExportSheet.PRODUCTS -> {
                    val workbookSheet = workbook.createSheet(sheet.technicalName)
                    writeHeaderRow(workbookSheet.createRow(0), schema.productHeaders)
                    content.products.forEachIndexed { index, details ->
                        writeProductDetailDataRow(workbookSheet, index + 1, details)
                    }
                }

                DatabaseExportSheet.SUPPLIERS -> {
                    val workbookSheet = workbook.createSheet(sheet.technicalName)
                    writeHeaderRow(workbookSheet.createRow(0), schema.supplierHeaders)
                    content.suppliers.forEachIndexed { index, supplier ->
                        val row = workbookSheet.createRow(index + 1)
                        row.createCell(0).setCellValue(supplier.id.toDouble())
                        row.createCell(1).setCellValue(supplier.name)
                    }
                }

                DatabaseExportSheet.CATEGORIES -> {
                    val workbookSheet = workbook.createSheet(sheet.technicalName)
                    writeHeaderRow(workbookSheet.createRow(0), schema.categoryHeaders)
                    content.categories.forEachIndexed { index, category ->
                        val row = workbookSheet.createRow(index + 1)
                        row.createCell(0).setCellValue(category.id.toDouble())
                        row.createCell(1).setCellValue(category.name)
                    }
                }

                DatabaseExportSheet.PRICE_HISTORY -> {
                    val workbookSheet = workbook.createSheet(sheet.technicalName)
                    writeHeaderRow(workbookSheet.createRow(0), schema.priceHistoryHeaders)

                    var previousGroupKey: String? = null
                    var previousPrice: Double? = null

                    content.priceHistoryRows.forEachIndexed { index, entry ->
                        val row = workbookSheet.createRow(index + 1)
                        val normalizedType = entry.type.uppercase(Locale.ROOT)
                        val groupKey = "${entry.barcode}|$normalizedType"

                        if (groupKey != previousGroupKey) {
                            previousGroupKey = groupKey
                            previousPrice = null
                        }

                        row.createCell(0).setCellValue(entry.barcode)
                        row.createCell(1).setCellValue(entry.timestamp)
                        row.createCell(2).setCellValue(
                            if (normalizedType.startsWith("PUR")) {
                                DatabaseExportConstants.PRICE_TYPE_PURCHASE
                            } else {
                                DatabaseExportConstants.PRICE_TYPE_RETAIL
                            }
                        )
                        previousPrice?.let { row.createCell(3).setCellValue(it) } ?: row.createCell(3)
                        row.createCell(4).setCellValue(entry.price)
                        row.createCell(5).setCellValue(entry.source.orEmpty())

                        previousPrice = entry.price
                    }
                }
            }

            onSheetWritten?.invoke(sheet)
        }

        workbook.write(outputStream)
        outputStream.flush()
    } finally {
        runCatching { workbook.close() }
        runCatching { workbook.dispose() }
    }
}

/**
 * Export DB senza materializzare l’intero catalogo né la cronologia prezzi in una singola lista:
 * legge dal repository a pagine e scrive su foglio mentre avanza.
 * Suppliers/categories restano liste compatte (dataset tipicamente piccolo).
 */
@Suppress("DEPRECATION")
suspend fun writeDatabaseExportStreaming(
    outputStream: OutputStream,
    selection: ExportSheetSelection,
    schema: DatabaseExportSchema,
    suppliers: List<Supplier>,
    categories: List<Category>,
    fetchProductPage: suspend (limit: Int, offset: Int) -> List<ProductWithDetails>,
    fetchPriceHistoryPage: suspend (limit: Int, offset: Int) -> List<PriceHistoryExportRow>,
    pageSize: Int = DatabaseExportConstants.DATABASE_EXPORT_PAGE_SIZE,
    onBeforeProductsSheet: () -> Unit = {},
    onAfterFirstProductPageFetched: () -> Unit = {},
    onBeforePriceHistorySheet: () -> Unit = {},
    onAfterFirstPriceHistoryPageFetched: () -> Unit = {},
    onSheetWritten: ((DatabaseExportSheet) -> Unit)? = null
) {
    require(!selection.isEmpty) { "At least one sheet must be selected." }
    require(pageSize > 0) { "pageSize must be positive." }

    val workbook = SXSSFWorkbook(100)
    workbook.isCompressTempFiles = true

    try {
        selection.selectedSheetsInOrder().forEach { sheet ->
            when (sheet) {
                DatabaseExportSheet.PRODUCTS -> {
                    onBeforeProductsSheet()
                    val workbookSheet = workbook.createSheet(sheet.technicalName)
                    writeHeaderRow(workbookSheet.createRow(0), schema.productHeaders)
                    var offset = 0
                    var rowIndex = 1
                    var firstFetch = true
                    while (true) {
                        val page = fetchProductPage(pageSize, offset)
                        if (firstFetch) {
                            onAfterFirstProductPageFetched()
                            firstFetch = false
                        }
                        if (page.isEmpty()) break
                        for (details in page) {
                            writeProductDetailDataRow(workbookSheet, rowIndex++, details)
                        }
                        offset += page.size
                    }
                }

                DatabaseExportSheet.SUPPLIERS -> {
                    val workbookSheet = workbook.createSheet(sheet.technicalName)
                    writeHeaderRow(workbookSheet.createRow(0), schema.supplierHeaders)
                    suppliers.forEachIndexed { index, supplier ->
                        val row = workbookSheet.createRow(index + 1)
                        row.createCell(0).setCellValue(supplier.id.toDouble())
                        row.createCell(1).setCellValue(supplier.name)
                    }
                }

                DatabaseExportSheet.CATEGORIES -> {
                    val workbookSheet = workbook.createSheet(sheet.technicalName)
                    writeHeaderRow(workbookSheet.createRow(0), schema.categoryHeaders)
                    categories.forEachIndexed { index, category ->
                        val row = workbookSheet.createRow(index + 1)
                        row.createCell(0).setCellValue(category.id.toDouble())
                        row.createCell(1).setCellValue(category.name)
                    }
                }

                DatabaseExportSheet.PRICE_HISTORY -> {
                    onBeforePriceHistorySheet()
                    val workbookSheet = workbook.createSheet(sheet.technicalName)
                    writeHeaderRow(workbookSheet.createRow(0), schema.priceHistoryHeaders)

                    var previousGroupKey: String? = null
                    var previousPrice: Double? = null
                    var offset = 0
                    var rowIndex = 1
                    var firstFetch = true
                    while (true) {
                        val page = fetchPriceHistoryPage(pageSize, offset)
                        if (firstFetch) {
                            onAfterFirstPriceHistoryPageFetched()
                            firstFetch = false
                        }
                        if (page.isEmpty()) break
                        for (entry in page) {
                            val row = workbookSheet.createRow(rowIndex++)
                            val normalizedType = entry.type.uppercase(Locale.ROOT)
                            val groupKey = "${entry.barcode}|$normalizedType"

                            if (groupKey != previousGroupKey) {
                                previousGroupKey = groupKey
                                previousPrice = null
                            }

                            row.createCell(0).setCellValue(entry.barcode)
                            row.createCell(1).setCellValue(entry.timestamp)
                            row.createCell(2).setCellValue(
                                if (normalizedType.startsWith("PUR")) {
                                    DatabaseExportConstants.PRICE_TYPE_PURCHASE
                                } else {
                                    DatabaseExportConstants.PRICE_TYPE_RETAIL
                                }
                            )
                            previousPrice?.let { row.createCell(3).setCellValue(it) } ?: row.createCell(3)
                            row.createCell(4).setCellValue(entry.price)
                            row.createCell(5).setCellValue(entry.source.orEmpty())

                            previousPrice = entry.price
                        }
                        offset += page.size
                    }
                }
            }

            onSheetWritten?.invoke(sheet)
        }

        workbook.write(outputStream)
        outputStream.flush()
    } finally {
        runCatching { workbook.close() }
        runCatching { workbook.dispose() }
    }
}

private fun writeHeaderRow(row: Row, headers: List<String>) {
    headers.forEachIndexed { index, header ->
        row.createCell(index).setCellValue(header)
    }
}

private fun writeProductDetailDataRow(sheet: Sheet, rowIndex: Int, details: ProductWithDetails) {
    val product = details.product
    val row = sheet.createRow(rowIndex)
    row.createCell(0).setCellValue(product.barcode)
    row.createCell(1).setCellValue(product.itemNumber.orEmpty())
    row.createCell(2).setCellValue(product.productName.orEmpty())
    row.createCell(3).setCellValue(product.secondProductName.orEmpty())
    row.createCell(4).setCellValue(product.purchasePrice ?: 0.0)
    row.createCell(5).setCellValue(product.retailPrice ?: 0.0)
    row.createCell(6).setCellValue(details.prevPurchase ?: 0.0)
    row.createCell(7).setCellValue(details.prevRetail ?: 0.0)
    row.createCell(8).setCellValue(details.supplierName.orEmpty())
    row.createCell(9).setCellValue(details.categoryName.orEmpty())
    row.createCell(10).setCellValue(product.stockQuantity ?: 0.0)
}
