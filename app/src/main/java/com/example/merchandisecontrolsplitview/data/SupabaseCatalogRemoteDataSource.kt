package com.example.merchandisecontrolsplitview.data

import com.example.merchandisecontrolsplitview.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator

/**
 * Transport PostgREST per il catalogo (task 013). Nessun accesso a Room.
 * Task 022: fetch catalogo paginato (`order id` + range) per superare `max_rows` PostgREST.
 */
class SupabaseCatalogRemoteDataSource(
    private val client: SupabaseClient?,
) : CatalogRemoteDataSource {

    override val isConfigured: Boolean get() = client != null

    private fun requireClient(): SupabaseClient =
        client ?: error("Supabase non configurato")

    override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>): Result<Unit> =
        runCatching {
            if (rows.isEmpty()) return@runCatching
            requireClient().postgrest["inventory_suppliers"].upsert(rows) {
                onConflict = "id"
            }
        }

    override suspend fun upsertSuppliers(rows: List<InventorySupplierRow>, shopId: String?): Result<Unit> =
        upsertSuppliers(rows.map { it.copy(shopId = shopId) })

    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>): Result<Unit> =
        runCatching {
            if (rows.isEmpty()) return@runCatching
            requireClient().postgrest["inventory_categories"].upsert(rows) {
                onConflict = "id"
            }
        }

    override suspend fun upsertCategories(rows: List<InventoryCategoryRow>, shopId: String?): Result<Unit> =
        upsertCategories(rows.map { it.copy(shopId = shopId) })

    override suspend fun upsertProducts(rows: List<InventoryProductRow>): Result<Unit> =
        runCatching {
            if (rows.isEmpty()) return@runCatching
            requireClient().postgrest["inventory_products"].upsert(rows) {
                onConflict = "id"
            }
        }

    override suspend fun upsertProducts(rows: List<InventoryProductRow>, shopId: String?): Result<Unit> =
        upsertProducts(rows.map { it.copy(shopId = shopId) })

    override suspend fun patchProduct(
        id: String,
        ownerUserId: String,
        patch: InventoryProductPatch
    ): Result<Unit> =
        runCatching {
            if (patch.isEmpty) return@runCatching
            requireClient().postgrest["inventory_products"].update(
                update = {
                    if (patch.includes("barcode")) set("barcode", patch.barcode)
                    if (patch.includes("itemnumber")) set("item_number", patch.itemNumber)
                    if (patch.includes("productname")) set("product_name", patch.productName)
                    if (patch.includes("secondproductname")) set("second_product_name", patch.secondProductName)
                    if (patch.includes("purchaseprice")) set("purchase_price", patch.purchasePrice)
                    if (patch.includes("retailprice")) set("retail_price", patch.retailPrice)
                    if (patch.includes("supplier")) set("supplier_id", patch.supplierId)
                    if (patch.includes("category")) set("category_id", patch.categoryId)
                    if (patch.includes("stockquantity")) set("stock_quantity", patch.stockQuantity)
                    if (patch.includes("tombstone")) set("deleted_at", patch.deletedAt)
                },
                request = {
                    filter {
                        eq("id", id)
                        eq("owner_user_id", ownerUserId)
                    }
                }
            )
        }

    override suspend fun patchProduct(
        id: String,
        ownerUserId: String,
        shopId: String?,
        patch: InventoryProductPatch
    ): Result<Unit> =
        runCatching {
            if (patch.isEmpty) return@runCatching
            requireClient().postgrest["inventory_products"].update(
                update = {
                    if (patch.includes("barcode")) set("barcode", patch.barcode)
                    if (patch.includes("itemnumber")) set("item_number", patch.itemNumber)
                    if (patch.includes("productname")) set("product_name", patch.productName)
                    if (patch.includes("secondproductname")) set("second_product_name", patch.secondProductName)
                    if (patch.includes("purchaseprice")) set("purchase_price", patch.purchasePrice)
                    if (patch.includes("retailprice")) set("retail_price", patch.retailPrice)
                    if (patch.includes("supplier")) set("supplier_id", patch.supplierId)
                    if (patch.includes("category")) set("category_id", patch.categoryId)
                    if (patch.includes("stockquantity")) set("stock_quantity", patch.stockQuantity)
                    if (patch.includes("tombstone")) set("deleted_at", patch.deletedAt)
                },
                request = {
                    filter {
                        eq("id", id)
                        eq("owner_user_id", ownerUserId)
                        if (!shopId.isNullOrBlank()) {
                            eq("shop_id", shopId)
                        }
                    }
                }
            )
        }

    override suspend fun fetchCatalog(): Result<InventoryCatalogFetchBundle> =
        runCatching {
            val pg = requireClient().postgrest
            val suppliers = pg.fetchInventoryTableAllPagesOrderedById<InventorySupplierRow>("inventory_suppliers")
            val categories = pg.fetchInventoryTableAllPagesOrderedById<InventoryCategoryRow>("inventory_categories")
            val products = pg.fetchInventoryTableAllPagesOrderedById<InventoryProductRow>("inventory_products")
            InventoryCatalogFetchBundle(suppliers, categories, products)
        }

    override suspend fun fetchCatalog(shopId: String?): Result<InventoryCatalogFetchBundle> =
        if (shopId.isNullOrBlank()) {
            fetchCatalog()
        } else {
            runCatching {
                val pg = requireClient().postgrest
                val suppliers = pg.fetchInventoryTableAllPagesOrderedById<InventorySupplierRow>(
                    "inventory_suppliers",
                    shopId
                )
                val categories = pg.fetchInventoryTableAllPagesOrderedById<InventoryCategoryRow>(
                    "inventory_categories",
                    shopId
                )
                val products = pg.fetchInventoryTableAllPagesOrderedById<InventoryProductRow>(
                    "inventory_products",
                    shopId
                )
                InventoryCatalogFetchBundle(suppliers, categories, products)
            }
        }

    override suspend fun fetchCatalogByIds(
        supplierIds: Set<String>,
        categoryIds: Set<String>,
        productIds: Set<String>
    ): Result<InventoryCatalogFetchBundle> =
        runCatching {
            val pg = requireClient().postgrest
            InventoryCatalogFetchBundle(
                suppliers = pg.fetchInventoryRowsByIds("inventory_suppliers", supplierIds),
                categories = pg.fetchInventoryRowsByIds("inventory_categories", categoryIds),
                products = pg.fetchInventoryRowsByIds("inventory_products", productIds),
                isCompleteSnapshot = false
            )
        }

    override suspend fun fetchCatalogByIds(
        supplierIds: Set<String>,
        categoryIds: Set<String>,
        productIds: Set<String>,
        shopId: String?
    ): Result<InventoryCatalogFetchBundle> =
        if (shopId.isNullOrBlank()) {
            fetchCatalogByIds(supplierIds, categoryIds, productIds)
        } else {
            runCatching {
                val pg = requireClient().postgrest
                InventoryCatalogFetchBundle(
                    suppliers = pg.fetchInventoryRowsByIds("inventory_suppliers", supplierIds, shopId),
                    categories = pg.fetchInventoryRowsByIds("inventory_categories", categoryIds, shopId),
                    products = pg.fetchInventoryRowsByIds("inventory_products", productIds, shopId),
                    isCompleteSnapshot = false
                )
            }
        }

    internal suspend fun fetchTask087CatalogByBarcodes(barcodes: Set<String>): Result<InventoryCatalogFetchBundle> =
        runCatching {
            require(BuildConfig.DEBUG) { "TASK087 smoke disabled" }
            require(barcodes.isNotEmpty()) { "TASK087 barcode set is empty" }
            require(barcodes.all { it in TASK087_BARCODES }) { "TASK087 barcode scope rejected" }

            val pg = requireClient().postgrest
            val products = mutableListOf<InventoryProductRow>()
            for (chunk in barcodes.chunked(TARGETED_FETCH_CHUNK)) {
                products += pg["inventory_products"].select {
                    filter {
                        isIn("barcode", chunk)
                    }
                }.decodeList()
            }
            require(products.size == barcodes.size && products.map { it.barcode }.toSet() == barcodes) {
                "TASK087 product read-back mismatch"
            }

            require(products.all { row ->
                row.deletedAt == null &&
                    row.barcode in TASK087_BARCODES &&
                    row.barcode.startsWith(TASK087_PREFIX)
            }) {
                "TASK087 product scope rejected"
            }

            val supplierIds = products.mapNotNull { it.supplierId }.toSet()
            val categoryIds = products.mapNotNull { it.categoryId }.toSet()
            require(supplierIds.isNotEmpty() && categoryIds.isNotEmpty()) {
                "TASK087 parent reference missing"
            }
            val suppliers = pg.fetchInventoryRowsByIds<InventorySupplierRow>(
                "inventory_suppliers",
                supplierIds
            )
            val categories = pg.fetchInventoryRowsByIds<InventoryCategoryRow>(
                "inventory_categories",
                categoryIds
            )
            require(suppliers.map { it.id }.toSet() == supplierIds) {
                "TASK087 supplier read-back mismatch"
            }
            require(categories.map { it.id }.toSet() == categoryIds) {
                "TASK087 category read-back mismatch"
            }

            require(suppliers.all { it.deletedAt == null && it.name.startsWith(TASK087_PREFIX) }) {
                "TASK087 supplier scope rejected"
            }
            require(categories.all { it.deletedAt == null && it.name.startsWith(TASK087_PREFIX) }) {
                "TASK087 category scope rejected"
            }

            InventoryCatalogFetchBundle(suppliers, categories, products, isCompleteSnapshot = false)
        }

    override suspend fun markSupplierTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
        patchTombstone("inventory_suppliers", patch)

    override suspend fun markCategoryTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
        patchTombstone("inventory_categories", patch)

    override suspend fun markProductTombstoned(patch: CatalogTombstonePatch): Result<Unit> =
        patchTombstone("inventory_products", patch)

    private suspend fun patchTombstone(table: String, patch: CatalogTombstonePatch): Result<Unit> =
        runCatching {
            requireClient().postgrest[table].update(
                update = {
                    set("deleted_at", patch.deletedAt)
                    set("updated_at", patch.updatedAt)
                },
                request = {
                    filter {
                        eq("id", patch.id)
                        eq("owner_user_id", patch.ownerUserId)
                        if (!patch.shopId.isNullOrBlank()) {
                            eq("shop_id", patch.shopId)
                        }
                        filter("deleted_at", FilterOperator.IS, "null")
                    }
                }
            )
        }

    private suspend inline fun <reified T : Any> Postgrest.fetchInventoryTableAllPagesOrderedById(
        table: String,
        shopId: String
    ): List<T> =
        fetchAllPagesByIndexedRange(
            pageSize = INVENTORY_REMOTE_PAGE_SIZE,
            maxPageIterations = INVENTORY_REMOTE_PAGE_FETCH_MAX_ITERATIONS,
            tableLabel = "$table shop-scoped"
        ) { from, to ->
            this[table].select {
                filter {
                    eq("shop_id", shopId)
                }
                order("id", Order.ASCENDING)
                range(from, to)
            }.decodeList()
        }

    private suspend inline fun <reified T : Any> io.github.jan.supabase.postgrest.Postgrest.fetchInventoryRowsByIds(
        table: String,
        remoteIds: Set<String>,
        shopId: String? = null
    ): List<T> {
        if (remoteIds.isEmpty()) return emptyList()
        val rows = mutableListOf<T>()
        for (chunk in remoteIds.chunked(TARGETED_FETCH_CHUNK)) {
            rows += this[table].select {
                filter {
                    isIn("id", chunk)
                    if (!shopId.isNullOrBlank()) {
                        eq("shop_id", shopId)
                    }
                }
            }.decodeList<T>()
        }
        return rows
    }

    private companion object {
        const val TARGETED_FETCH_CHUNK = 80
        const val TASK087_PREFIX = "TASK087_"
        val TASK087_BARCODES = setOf("TASK087_BAR_A", "TASK087_BAR_I")
    }
}
