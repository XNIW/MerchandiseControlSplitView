package com.example.merchandisecontrolsplitview.util

import android.app.Application
import android.net.Uri
import androidx.room.Room
import com.example.merchandisecontrolsplitview.data.AppDatabase
import com.example.merchandisecontrolsplitview.data.DefaultInventoryRepository
import com.example.merchandisecontrolsplitview.data.ImportRowSource
import com.example.merchandisecontrolsplitview.data.Product
import java.io.File
import kotlinx.coroutines.test.runTest
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FullDbCatalogTextDiagnosticsTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var repository: DefaultInventoryRepository

    @Before
    fun setup() {
        app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DefaultInventoryRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `full database preserves typed supplier and category warnings and redacted errors`() = runTest {
        val file = createFullWorkbook(
            supplierNames = listOf("", "  Supplier\nOne  ", "Unsafe\u200BSupplier"),
            categoryNames = listOf("\tCategory  One ", "Unsafe\u202ECategory")
        )

        val result = analyzeFullDbImportStreaming(
            context = app,
            uri = Uri.fromFile(file),
            currentDbProducts = emptyList(),
            repository = repository
        )
        val analysis = result.analysis.analysis

        assertEquals(setOf("Supplier One"), result.pendingSupplierNames)
        assertEquals(setOf("Category One"), result.pendingCategoryNames)
        assertEquals(2, result.supplierRowCount)
        assertEquals(2, result.categoryRowCount)
        assertEquals(2, analysis.totalTextNormalizationRowCount)
        assertEquals(2, analysis.totalTextNormalizationFieldCount)
        assertEquals(
            setOf(ImportRowSource.SUPPLIERS, ImportRowSource.CATEGORIES),
            analysis.textNormalizationWarnings.map { it.source }.toSet()
        )
        assertEquals(
            mapOf(
                ImportRowSource.SUPPLIERS to 3,
                ImportRowSource.CATEGORIES to 2
            ),
            analysis.textNormalizationWarnings.associate { it.source to it.rowNumber }
        )
        assertEquals(
            setOf(CatalogTextField.SUPPLIER_NAME, CatalogTextField.CATEGORY_NAME),
            analysis.textNormalizationWarnings.flatMap { it.fields }.toSet()
        )
        assertEquals(2, analysis.totalErrorCount)
        assertEquals(
            setOf(ImportRowSource.SUPPLIERS, ImportRowSource.CATEGORIES),
            analysis.errors.map { it.source }.toSet()
        )
        assertEquals(
            mapOf(
                ImportRowSource.SUPPLIERS to 4,
                ImportRowSource.CATEGORIES to 3
            ),
            analysis.errors.associate { it.source to it.rowNumber }
        )
        assertTrue(analysis.errors.all { it.redactedFields.isNotEmpty() })
        assertFalse(
            analysis.errors.flatMap { it.rowContent.values }
                .any { it.contains('\u200B') || it.contains('\u202E') }
        )
    }

    @Test
    fun `full database bounds sampled entity errors while preserving total count`() = runTest {
        val rejectedSuppliers = (1..501).map { index -> "Supplier-$index\u200B" }
        val rejectedCategories = (1..501).map { index -> "Category-$index\u202E" }
        val file = createFullWorkbook(
            supplierNames = rejectedSuppliers,
            categoryNames = rejectedCategories
        )

        val result = analyzeFullDbImportStreaming(
            context = app,
            uri = Uri.fromFile(file),
            currentDbProducts = emptyList(),
            repository = repository
        )
        val analysis = result.analysis.analysis

        assertEquals(501, result.supplierRowCount)
        assertEquals(501, result.categoryRowCount)
        assertEquals(1_002, analysis.totalErrorCount)
        assertEquals(1_000, analysis.errors.size)
        assertEquals(
            mapOf(
                ImportRowSource.SUPPLIERS to 500,
                ImportRowSource.CATEGORIES to 500
            ),
            analysis.errors.groupingBy { it.source }.eachCount()
        )
    }

    @Test
    fun `full database blocks price history barcode collision after trim`() = runTest {
        val file = createFullWorkbook(
            priceRows = listOf(
                listOf(" FULL-PRICE-001 ", "2026-07-27 10:00:00", "RETAIL", "10"),
                listOf("FULL-PRICE-001", "2026-07-27 11:00:00", "RETAIL", "11")
            )
        )

        val failure = runCatching {
            analyzeFullDbImportStreaming(
                context = app,
                uri = Uri.fromFile(file),
                currentDbProducts = emptyList(),
                repository = repository
            )
        }.exceptionOrNull()

        assertTrue(failure is CatalogTextValidationException)
        failure as CatalogTextValidationException
        assertEquals(CatalogTextField.BARCODE, failure.rejection.field)
        assertEquals(
            CatalogTextPolicy.RejectionReason.IDENTITY_COLLISION_AFTER_TRIM,
            failure.rejection.reason
        )
    }

    @Test
    fun `full database blocks same barcode item number collision before last row wins`() = runTest {
        val file = createFullWorkbook(
            productRows = listOf(
                listOf("FULL-SAME-001", " ITEM-SAME ", "First", "12"),
                listOf("FULL-SAME-001", "ITEM-SAME", "Last", "13")
            )
        )

        val result = analyzeFullDbImportStreaming(
            context = app,
            uri = Uri.fromFile(file),
            currentDbProducts = emptyList(),
            repository = repository
        )
        val analysis = result.analysis.analysis

        assertTrue(analysis.newProducts.isEmpty())
        assertTrue(analysis.updatedProducts.isEmpty())
        assertEquals(1, analysis.totalErrorCount)
        assertEquals(2, analysis.errors.single().rowNumber)
        assertEquals(
            app.getString(CatalogTextField.ITEM_NUMBER.labelResource()),
            analysis.errors.single().formatArgs.first()
        )
    }

    @Test
    fun `legacy streaming price apply preflights collision before writing any batch`() = runTest {
        db.productDao().insert(
            Product(
                barcode = "FULL-PRICE-001",
                productName = "Price Product",
                retailPrice = 11.0
            )
        )
        val file = createFullWorkbook(
            priceRows = listOf(
                listOf(" FULL-PRICE-001 ", "2026-07-27 10:00:00", "RETAIL", "10"),
                listOf("FULL-PRICE-001", "2026-07-27 11:00:00", "RETAIL", "11")
            )
        )

        val failure = runCatching {
            applyFullDbPriceHistoryStreaming(app, Uri.fromFile(file), repository)
        }.exceptionOrNull()

        assertTrue(failure is CatalogTextValidationException)
        assertTrue(repository.getAllPriceHistoryRows().isEmpty())
    }

    private fun createFullWorkbook(
        productRows: List<List<String>> = emptyList(),
        supplierNames: List<String> = emptyList(),
        categoryNames: List<String> = emptyList(),
        priceRows: List<List<String>> = emptyList()
    ): File {
        val file = File.createTempFile("full-db-catalog-text", ".xlsx", app.cacheDir)
        XSSFWorkbook().use { workbook ->
            workbook.createSheet("Products").apply {
                createRow(0).apply {
                    createCell(0).setCellValue("Barcode")
                    createCell(1).setCellValue("Item Number")
                    createCell(2).setCellValue("Product Name")
                    createCell(3).setCellValue("Retail Price")
                }
                val rows = productRows.ifEmpty {
                    listOf(listOf("FULL-PRODUCT-001", "FULL-ITEM-001", "Full Product", "12"))
                }
                rows.forEachIndexed { rowIndex, values ->
                    createRow(rowIndex + 1).apply {
                        values.forEachIndexed { cellIndex, value ->
                            createCell(cellIndex).setCellValue(value)
                        }
                    }
                }
            }
            if (supplierNames.isNotEmpty()) {
                workbook.createSheet("Suppliers").apply {
                    createRow(0).createCell(0).setCellValue("Name")
                    supplierNames.forEachIndexed { index, name ->
                        createRow(index + 1).createCell(0).setCellValue(name)
                    }
                }
            }
            if (categoryNames.isNotEmpty()) {
                workbook.createSheet("Categories").apply {
                    createRow(0).createCell(0).setCellValue("Name")
                    categoryNames.forEachIndexed { index, name ->
                        createRow(index + 1).createCell(0).setCellValue(name)
                    }
                }
            }
            if (priceRows.isNotEmpty()) {
                workbook.createSheet("PriceHistory").apply {
                    createRow(0).apply {
                        listOf("productBarcode", "timestamp", "type", "newPrice")
                            .forEachIndexed { index, header ->
                                createCell(index).setCellValue(header)
                            }
                    }
                    priceRows.forEachIndexed { rowIndex, values ->
                        createRow(rowIndex + 1).apply {
                            values.forEachIndexed { cellIndex, value ->
                                createCell(cellIndex).setCellValue(value)
                            }
                        }
                    }
                }
            }
            file.outputStream().use(workbook::write)
        }
        return file
    }
}
