package com.example.merchandisecontrolsplitview.util

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Looper
import androidx.room.Room
import com.example.merchandisecontrolsplitview.R
import com.example.merchandisecontrolsplitview.data.AppDatabase
import com.example.merchandisecontrolsplitview.data.DefaultInventoryRepository
import com.example.merchandisecontrolsplitview.data.InventoryRepository
import com.example.merchandisecontrolsplitview.data.PriceHistoryExportRow
import com.example.merchandisecontrolsplitview.data.Product
import com.example.merchandisecontrolsplitview.data.ProductPrice
import com.example.merchandisecontrolsplitview.data.ProductWithDetails
import com.example.merchandisecontrolsplitview.testutil.MainDispatcherRule
import com.example.merchandisecontrolsplitview.viewmodel.DatabaseViewModel
import com.example.merchandisecontrolsplitview.viewmodel.UiState
import java.io.File
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FullDbExportImportRoundTripTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var app: Application
    private val openedDatabases = mutableListOf<AppDatabase>()

    @Before
    fun setup() {
        app = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        openedDatabases.asReversed().forEach(AppDatabase::close)
        openedDatabases.clear()
    }

    @Test
    fun `RT-FULL round trip keeps products suppliers categories and non synthetic price history`() =
        runTest {
            val sourceDb = createInMemoryDb()
            val sourceRepository = DefaultInventoryRepository(sourceDb)
            val expected = seedStandardRoundTripFixture(sourceDb, sourceRepository)
            val workbookFile = exportFullDatabase(app, sourceRepository, "rt-full")

            assertWorkbookOpenable(workbookFile)
            assertEquals(
                ImportWorkbookRoute.FULL_DATABASE,
                detectImportWorkbookRoute(app, Uri.fromFile(workbookFile))
            )

            val targetDb = createInMemoryDb()
            val targetRepository = DefaultInventoryRepository(targetDb)
            importWorkbookIntoTarget(app, workbookFile, targetRepository)

            assertEquals(expected.products, exportedProductRows(targetRepository))
            assertEquals(expected.suppliers, targetRepository.getAllSuppliers().map { it.name })
            assertEquals(expected.categories, targetRepository.getAllCategories().map { it.name })

            val targetHistory = normalizedHistoryRows(targetRepository.getAllPriceHistoryRows())
            assertEquals(expected.nonSyntheticHistory, targetHistory.filterNot(::isSyntheticImportRow))

            val syntheticRows = targetHistory.filter(::isSyntheticImportRow)
            assertEquals(expected.syntheticRowCount, syntheticRows.size)
            assertEquals(setOf("IMPORT", "IMPORT_PREV"), syntheticRows.mapNotNull { it.source }.toSet())
        }

    @Test
    fun `RT-NOPH round trip without PriceHistory sheet keeps exported product snapshot and only synthetic history`() =
        runTest {
            val sourceDb = createInMemoryDb()
            val sourceRepository = DefaultInventoryRepository(sourceDb)
            val expected = seedStandardRoundTripFixture(sourceDb, sourceRepository)
            val workbookFile = exportFullDatabase(app, sourceRepository, "rt-noph")
            val workbookWithoutPriceHistory = copyWorkbookWithoutSheet(workbookFile, "PriceHistory")

            assertWorkbookOpenable(workbookWithoutPriceHistory)
            assertEquals(
                ImportWorkbookRoute.FULL_DATABASE,
                detectImportWorkbookRoute(app, Uri.fromFile(workbookWithoutPriceHistory))
            )

            val targetDb = createInMemoryDb()
            val targetRepository = DefaultInventoryRepository(targetDb)
            val importResult = importWorkbookIntoTarget(app, workbookWithoutPriceHistory, targetRepository)

            assertTrue(importResult.hasPriceHistorySheet.not())
            assertEquals(expected.products, exportedProductRows(targetRepository))
            assertEquals(expected.suppliers, targetRepository.getAllSuppliers().map { it.name })
            assertEquals(expected.categories, targetRepository.getAllCategories().map { it.name })

            val targetHistory = normalizedHistoryRows(targetRepository.getAllPriceHistoryRows())
            assertTrue(targetHistory.all(::isSyntheticImportRow))
            assertEquals(expected.syntheticRowCount, targetHistory.size)
        }

    @Test
    fun `RT-LOCALE localized Products headers stay importable`() = runTest {
        val sourceDb = createInMemoryDb()
        val sourceRepository = DefaultInventoryRepository(sourceDb)
        val expected = seedStandardRoundTripFixture(sourceDb, sourceRepository)
        val spanishContext = localizedContext(Locale.forLanguageTag("es"))
        val workbookFile = exportFullDatabase(spanishContext, sourceRepository, "rt-locale")

        XSSFWorkbook(workbookFile.inputStream()).use { workbook ->
            val productsSheet = workbook.getSheet("Products")
            val barcodeHeader = productsSheet.getRow(0).getCell(0).stringCellValue
            assertEquals(spanishContext.getString(R.string.header_barcode), barcodeHeader)
            assertNotEquals("Barcode", barcodeHeader)
        }

        val targetDb = createInMemoryDb()
        val targetRepository = DefaultInventoryRepository(targetDb)
        importWorkbookIntoTarget(app, workbookFile, targetRepository)

        assertEquals(expected.products, exportedProductRows(targetRepository))
        assertEquals(expected.suppliers, targetRepository.getAllSuppliers().map { it.name })
        assertEquals(expected.categories, targetRepository.getAllCategories().map { it.name })
    }

    @Test
    fun `RT-PART keeps real price history and adds expected IMPORT rows separately`() = runTest {
        val sourceDb = createInMemoryDb()
        val sourceRepository = DefaultInventoryRepository(sourceDb)
        val expected = seedPartitionFixture(sourceDb, sourceRepository)
        val workbookFile = exportFullDatabase(app, sourceRepository, "rt-part")

        val targetDb = createInMemoryDb()
        val targetRepository = DefaultInventoryRepository(targetDb)
        importWorkbookIntoTarget(app, workbookFile, targetRepository)

        val targetHistory = normalizedHistoryRows(targetRepository.getAllPriceHistoryRows())
        val nonSynthetic = targetHistory.filterNot(::isSyntheticImportRow)
        val synthetic = targetHistory.filter(::isSyntheticImportRow)

        assertEquals(expected.nonSyntheticHistory, nonSynthetic)
        assertEquals(expected.syntheticRowCount, synthetic.size)
        assertEquals(2, synthetic.count { it.source == "IMPORT" })
        assertEquals(2, synthetic.count { it.source == "IMPORT_PREV" })
    }

    @Test
    fun `EX-SIGNIFICANT export full DB succeeds on realistic fixture and produces openable workbook`() =
        runTest {
            val sourceDb = createInMemoryDb()
            val sourceRepository = DefaultInventoryRepository(sourceDb)
            val productCount = 1200
            val historyRowsPerProduct = 4
            seedSignificantExportFixture(
                db = sourceDb,
                repository = sourceRepository,
                productCount = productCount,
                historyRowsPerProduct = historyRowsPerProduct
            )

            val workbookFile = exportFullDatabase(
                context = app,
                repository = sourceRepository,
                prefix = "ex-significant",
                timeoutMs = 30_000
            )

            assertTrue(workbookFile.length() > 0)
            XSSFWorkbook(workbookFile.inputStream()).use { workbook ->
                assertNotNull(workbook.getSheet("Products"))
                assertNotNull(workbook.getSheet("Suppliers"))
                assertNotNull(workbook.getSheet("Categories"))
                assertNotNull(workbook.getSheet("PriceHistory"))
                assertEquals(productCount + 1, workbook.getSheet("Products").physicalNumberOfRows)
                assertEquals(
                    (productCount * historyRowsPerProduct) + 1,
                    workbook.getSheet("PriceHistory").physicalNumberOfRows
                )
            }
        }

    private fun createInMemoryDb(): AppDatabase =
        Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also(openedDatabases::add)

    private fun localizedContext(locale: Locale): Context {
        val configuration = Configuration(app.resources.configuration)
        configuration.setLocale(locale)
        return app.createConfigurationContext(configuration)
    }

    private suspend fun exportFullDatabase(
        context: Context,
        repository: InventoryRepository,
        prefix: String,
        timeoutMs: Long = 10_000
    ): File {
        val file = File.createTempFile(prefix, ".xlsx", app.cacheDir)
        val viewModel = DatabaseViewModel(app, repository)

        viewModel.exportFullDbToExcel(context, Uri.fromFile(file))
        waitForCondition(timeoutMs) {
            viewModel.uiState.value is UiState.Success || viewModel.uiState.value is UiState.Error
        }

        assertEquals(
            UiState.Success(context.getString(R.string.export_success)),
            viewModel.uiState.value
        )
        return file
    }

    private suspend fun importWorkbookIntoTarget(
        context: Context,
        workbookFile: File,
        targetRepository: DefaultInventoryRepository
    ): FullDbImportStreamingResult {
        val workbookUri = Uri.fromFile(workbookFile)
        val importResult = analyzeFullDbImportStreaming(
            context = context,
            uri = workbookUri,
            currentDbProducts = targetRepository.getAllProducts(),
            repository = targetRepository
        )

        assertTrue(importResult.analysis.analysis.errors.isEmpty())

        val resolvedPayload = resolveImportPayloadForTest(targetRepository, importResult)
        targetRepository.applyImport(
            newProducts = resolvedPayload.newProducts,
            updatedProducts = resolvedPayload.updatedProducts
        )

        if (importResult.hasPriceHistorySheet) {
            applyFullDbPriceHistoryStreaming(context, workbookUri, targetRepository)
        }

        return importResult
    }

    private suspend fun resolveImportPayloadForTest(
        repository: InventoryRepository,
        importResult: FullDbImportStreamingResult
    ): ResolvedImportPayloadForTest {
        val analysis = importResult.analysis
        if (
            importResult.pendingSupplierNames.isEmpty() &&
            importResult.pendingCategoryNames.isEmpty() &&
            analysis.pendingSuppliers.isEmpty() &&
            analysis.pendingCategories.isEmpty()
        ) {
            return ResolvedImportPayloadForTest(
                newProducts = analysis.analysis.newProducts,
                updatedProducts = analysis.analysis.updatedProducts.map { it.newProduct.copy(id = it.oldProduct.id) }
            )
        }

        importResult.pendingSupplierNames.forEach { repository.addSupplier(it) }
        importResult.pendingCategoryNames.forEach { repository.addCategory(it) }

        val supplierIdsByName = repository.getAllSuppliers()
            .associateBy { it.name.trim().lowercase() }
        val categoryIdsByName = repository.getAllCategories()
            .associateBy { it.name.trim().lowercase() }

        fun resolveProduct(product: Product): Product {
            val resolvedSupplierId = when {
                product.supplierId == null -> null
                product.supplierId >= 0L -> product.supplierId
                else -> analysis.pendingSuppliers[product.supplierId]
                    ?.trim()
                    ?.lowercase()
                    ?.let { supplierIdsByName[it]?.id }
            }

            val resolvedCategoryId = when {
                product.categoryId == null -> null
                product.categoryId >= 0L -> product.categoryId
                else -> analysis.pendingCategories[product.categoryId]
                    ?.trim()
                    ?.lowercase()
                    ?.let { categoryIdsByName[it]?.id }
            }

            return product.copy(
                supplierId = resolvedSupplierId,
                categoryId = resolvedCategoryId
            )
        }

        return ResolvedImportPayloadForTest(
            newProducts = analysis.analysis.newProducts.map(::resolveProduct),
            updatedProducts = analysis.analysis.updatedProducts.map { update ->
                resolveProduct(update.newProduct).copy(id = update.oldProduct.id)
            }
        )
    }

    private suspend fun seedStandardRoundTripFixture(
        db: AppDatabase,
        repository: DefaultInventoryRepository
    ): FixtureExpectation {
        val supplierOne = repository.addSupplier("Supplier Alpha")!!
        val supplierTwo = repository.addSupplier("Supplier Beta")!!
        val categoryOne = repository.addCategory("Category One")!!
        val categoryTwo = repository.addCategory("Category Two")!!

        val productOne = Product(
            barcode = "00000001",
            itemNumber = "ITEM-001",
            productName = "Sparkling Water",
            secondProductName = "Acqua Frizzante",
            purchasePrice = 4.5,
            retailPrice = 6.8,
            supplierId = supplierOne.id,
            categoryId = categoryOne.id,
            stockQuantity = 12.0
        )
        val productTwo = Product(
            barcode = "00000002",
            itemNumber = "ITEM-002",
            productName = "Potato Chips",
            secondProductName = "Paprika",
            purchasePrice = 1.9,
            retailPrice = 3.1,
            supplierId = supplierTwo.id,
            categoryId = categoryTwo.id,
            stockQuantity = 8.0
        )

        db.productDao().insert(productOne)
        db.productDao().insert(productTwo)

        val persistedOne = db.productDao().findByBarcode(productOne.barcode)!!
        val persistedTwo = db.productDao().findByBarcode(productTwo.barcode)!!

        db.productPriceDao().insertAll(
            listOf(
                ProductPrice(
                    productId = persistedOne.id,
                    type = "PURCHASE",
                    price = 3.2,
                    effectiveAt = "2026-01-01 08:00:00",
                    source = "MANUAL"
                ),
                ProductPrice(
                    productId = persistedOne.id,
                    type = "PURCHASE",
                    price = 3.7,
                    effectiveAt = "2026-02-01 08:00:00",
                    source = "MANUAL"
                ),
                ProductPrice(
                    productId = persistedOne.id,
                    type = "RETAIL",
                    price = 5.4,
                    effectiveAt = "2026-01-01 08:00:00",
                    source = "MANUAL"
                ),
                ProductPrice(
                    productId = persistedOne.id,
                    type = "RETAIL",
                    price = 6.1,
                    effectiveAt = "2026-02-01 08:00:00",
                    source = "MANUAL"
                ),
                ProductPrice(
                    productId = persistedTwo.id,
                    type = "PURCHASE",
                    price = 1.2,
                    effectiveAt = "2026-01-15 09:00:00",
                    source = "MANUAL"
                ),
                ProductPrice(
                    productId = persistedTwo.id,
                    type = "PURCHASE",
                    price = 1.5,
                    effectiveAt = "2026-02-15 09:00:00",
                    source = "MANUAL"
                ),
                ProductPrice(
                    productId = persistedTwo.id,
                    type = "RETAIL",
                    price = 2.4,
                    effectiveAt = "2026-01-15 09:00:00",
                    source = "MANUAL"
                ),
                ProductPrice(
                    productId = persistedTwo.id,
                    type = "RETAIL",
                    price = 2.7,
                    effectiveAt = "2026-02-15 09:00:00",
                    source = "MANUAL"
                )
            )
        )

        return captureExpectation(repository, syntheticRowCount = 8)
    }

    private suspend fun seedPartitionFixture(
        db: AppDatabase,
        repository: DefaultInventoryRepository
    ): FixtureExpectation {
        val supplier = repository.addSupplier("Supplier Partition")!!
        val category = repository.addCategory("Category Partition")!!
        val product = Product(
            barcode = "00990099",
            itemNumber = "PART-001",
            productName = "Round Trip Anchor",
            secondProductName = "Partition",
            purchasePrice = 8.9,
            retailPrice = 11.9,
            supplierId = supplier.id,
            categoryId = category.id,
            stockQuantity = 4.0
        )

        db.productDao().insert(product)
        val persisted = db.productDao().findByBarcode(product.barcode)!!
        db.productPriceDao().insertAll(
            listOf(
                ProductPrice(
                    productId = persisted.id,
                    type = "PURCHASE",
                    price = 7.2,
                    effectiveAt = "2026-01-01 10:00:00",
                    source = "MANUAL"
                ),
                ProductPrice(
                    productId = persisted.id,
                    type = "PURCHASE",
                    price = 7.8,
                    effectiveAt = "2026-02-01 10:00:00",
                    source = "MANUAL"
                ),
                ProductPrice(
                    productId = persisted.id,
                    type = "RETAIL",
                    price = 10.4,
                    effectiveAt = "2026-01-01 10:00:00",
                    source = "MANUAL"
                ),
                ProductPrice(
                    productId = persisted.id,
                    type = "RETAIL",
                    price = 10.9,
                    effectiveAt = "2026-02-01 10:00:00",
                    source = "MANUAL"
                )
            )
        )

        return captureExpectation(repository, syntheticRowCount = 4)
    }

    private suspend fun seedSignificantExportFixture(
        db: AppDatabase,
        repository: DefaultInventoryRepository,
        productCount: Int,
        historyRowsPerProduct: Int
    ) {
        repeat(30) { repository.addSupplier("Supplier ${it + 1}") }
        repeat(20) { repository.addCategory("Category ${it + 1}") }

        val suppliers = repository.getAllSuppliers()
        val categories = repository.getAllCategories()

        val products = (1..productCount).map { index ->
            Product(
                barcode = index.toString().padStart(8, '0'),
                itemNumber = "SKU-$index",
                productName = "Product $index",
                secondProductName = "Alt $index",
                purchasePrice = 10.0 + (index % 7),
                retailPrice = 15.0 + (index % 9),
                supplierId = suppliers[(index - 1) % suppliers.size].id,
                categoryId = categories[(index - 1) % categories.size].id,
                stockQuantity = (index % 25).toDouble()
            )
        }
        db.productDao().insertAll(products)

        val persistedByBarcode = db.productDao().getAllLite().associateBy { it.barcode }
        val historyRows = buildList {
            products.forEachIndexed { index, product ->
                val persisted = persistedByBarcode.getValue(product.barcode)
                add(
                    ProductPrice(
                        productId = persisted.id,
                        type = "PURCHASE",
                        price = product.purchasePrice!! - 2.0,
                        effectiveAt = "2026-01-${((index % 27) + 1).toString().padStart(2, '0')} 08:00:00",
                        source = "MANUAL"
                    )
                )
                add(
                    ProductPrice(
                        productId = persisted.id,
                        type = "PURCHASE",
                        price = product.purchasePrice - 1.0,
                        effectiveAt = "2026-02-${((index % 27) + 1).toString().padStart(2, '0')} 08:00:00",
                        source = "MANUAL"
                    )
                )
                add(
                    ProductPrice(
                        productId = persisted.id,
                        type = "RETAIL",
                        price = product.retailPrice!! - 2.0,
                        effectiveAt = "2026-01-${((index % 27) + 1).toString().padStart(2, '0')} 09:00:00",
                        source = "MANUAL"
                    )
                )
                add(
                    ProductPrice(
                        productId = persisted.id,
                        type = "RETAIL",
                        price = product.retailPrice - 1.0,
                        effectiveAt = "2026-02-${((index % 27) + 1).toString().padStart(2, '0')} 09:00:00",
                        source = "MANUAL"
                    )
                )
            }
        }

        assertEquals(productCount * historyRowsPerProduct, historyRows.size)
        db.productPriceDao().insertAll(historyRows)
    }

    private suspend fun captureExpectation(
        repository: DefaultInventoryRepository,
        syntheticRowCount: Int
    ): FixtureExpectation = FixtureExpectation(
        products = exportedProductRows(repository),
        suppliers = repository.getAllSuppliers().map { it.name },
        categories = repository.getAllCategories().map { it.name },
        nonSyntheticHistory = normalizedHistoryRows(repository.getAllPriceHistoryRows()),
        syntheticRowCount = syntheticRowCount
    )

    private suspend fun exportedProductRows(
        repository: DefaultInventoryRepository
    ): List<ExportedProductRowSnapshot> =
        repository.getAllProductsWithDetails()
            .map(::toExportedProductRowSnapshot)
            .sortedBy { it.barcode }

    private fun toExportedProductRowSnapshot(details: ProductWithDetails): ExportedProductRowSnapshot {
        val product = details.product
        return ExportedProductRowSnapshot(
            barcode = product.barcode,
            itemNumber = product.itemNumber.orEmpty(),
            productName = product.productName.orEmpty(),
            secondProductName = product.secondProductName.orEmpty(),
            purchasePrice = product.purchasePrice ?: 0.0,
            retailPrice = product.retailPrice ?: 0.0,
            previousPurchasePrice = details.prevPurchase ?: 0.0,
            previousRetailPrice = details.prevRetail ?: 0.0,
            supplierName = details.supplierName.orEmpty(),
            categoryName = details.categoryName.orEmpty(),
            stockQuantity = product.stockQuantity ?: 0.0
        )
    }

    private fun normalizedHistoryRows(
        rows: List<PriceHistoryExportRow>
    ): List<HistoryRowSnapshot> = rows
        .map {
            HistoryRowSnapshot(
                barcode = it.barcode,
                timestamp = it.timestamp,
                type = it.type,
                price = it.price,
                source = it.source
            )
        }
        .sortedWith(compareBy<HistoryRowSnapshot> { it.barcode }
            .thenBy { it.type }
            .thenBy { it.timestamp }
            .thenBy { it.price }
            .thenBy { it.source.orEmpty() })

    private fun isSyntheticImportRow(row: HistoryRowSnapshot): Boolean =
        row.source == "IMPORT" || row.source == "IMPORT_PREV"

    private fun copyWorkbookWithoutSheet(source: File, sheetName: String): File {
        val target = File.createTempFile("without-sheet-", ".xlsx", app.cacheDir)
        XSSFWorkbook(source.inputStream()).use { workbook ->
            val sheetIndex = workbook.getSheetIndex(sheetName)
            assertTrue(sheetIndex >= 0)
            workbook.removeSheetAt(sheetIndex)
            target.outputStream().use(workbook::write)
        }
        return target
    }

    private fun assertWorkbookOpenable(workbookFile: File) {
        assertTrue(workbookFile.length() > 0)
        XSSFWorkbook(workbookFile.inputStream()).use { workbook ->
            assertNotNull(workbook.getSheet("Products"))
        }
    }

    private fun waitForCondition(
        timeoutMs: Long,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(25)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Condition not met within ${timeoutMs}ms", condition())
    }
}

private data class ExportedProductRowSnapshot(
    val barcode: String,
    val itemNumber: String,
    val productName: String,
    val secondProductName: String,
    val purchasePrice: Double,
    val retailPrice: Double,
    val previousPurchasePrice: Double,
    val previousRetailPrice: Double,
    val supplierName: String,
    val categoryName: String,
    val stockQuantity: Double
)

private data class HistoryRowSnapshot(
    val barcode: String,
    val timestamp: String,
    val type: String,
    val price: Double,
    val source: String?
)

private data class FixtureExpectation(
    val products: List<ExportedProductRowSnapshot>,
    val suppliers: List<String>,
    val categories: List<String>,
    val nonSyntheticHistory: List<HistoryRowSnapshot>,
    val syntheticRowCount: Int
)

private data class ResolvedImportPayloadForTest(
    val newProducts: List<Product>,
    val updatedProducts: List<Product>
)
