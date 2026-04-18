package com.example.merchandisecontrolsplitview.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppDatabaseMigrationTest {

    private lateinit var app: Application
    private val openedDatabases = mutableListOf<AppDatabase>()
    private val openedSupportHelpers = mutableListOf<SupportSQLiteOpenHelper>()
    private val databaseNames = mutableSetOf<String>()

    companion object {
        private const val RELEASED_V6_IDENTITY_HASH = "c52a22bb706c042a91802612b02570a4"
    }

    @Before
    fun setup() {
        app = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        openedDatabases.asReversed().forEach(AppDatabase::close)
        openedDatabases.clear()
        openedSupportHelpers.asReversed().forEach(SupportSQLiteOpenHelper::close)
        openedSupportHelpers.clear()
        databaseNames.forEach(app::deleteDatabase)
        databaseNames.clear()
    }

    @Test
    fun `migration 5 chain preserves products and matches fresh install schema at v7`() = runTest {
        val migratedName = "task009-migrated-v5-to-v7.db"
        val freshName = "task009-fresh-v7-products.db"

        createLegacyDatabase(migratedName, version = 5) { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS suppliers(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_suppliers_name ON suppliers(name)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS categories(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL COLLATE NOCASE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_categories_name ON categories(name)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS history_entries(
                    uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    id TEXT NOT NULL,
                    timestamp TEXT NOT NULL,
                    data TEXT NOT NULL,
                    editable TEXT NOT NULL,
                    complete TEXT NOT NULL,
                    supplier TEXT NOT NULL,
                    category TEXT NOT NULL,
                    wasExported INTEGER NOT NULL,
                    syncStatus TEXT NOT NULL,
                    orderTotal REAL NOT NULL,
                    paymentTotal REAL NOT NULL,
                    missingItems INTEGER NOT NULL,
                    totalItems INTEGER NOT NULL,
                    isManualEntry INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS products(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    barcode TEXT NOT NULL,
                    itemNumber TEXT,
                    productName TEXT,
                    secondProductName TEXT,
                    purchasePrice REAL,
                    retailPrice REAL,
                    oldPurchasePrice REAL,
                    oldRetailPrice REAL,
                    supplierId INTEGER,
                    categoryId INTEGER,
                    stockQuantity REAL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_products_barcode ON products(barcode)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_products_supplierId ON products(supplierId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_products_categoryId ON products(categoryId)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS product_prices(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    productId INTEGER NOT NULL,
                    type TEXT NOT NULL,
                    price REAL NOT NULL,
                    effectiveAt TEXT NOT NULL,
                    source TEXT,
                    note TEXT,
                    createdAt TEXT NOT NULL,
                    FOREIGN KEY(productId) REFERENCES products(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_prices_unique ON product_prices(productId,type,effectiveAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_prices_lookup ON product_prices(productId,type,createdAt)")

            db.execSQL("INSERT INTO suppliers(id, name) VALUES (10, 'Supplier MVC')")
            db.execSQL("INSERT INTO categories(id, name) VALUES (20, 'Category MVC')")
            db.execSQL(
                """
                INSERT INTO products(
                    id,
                    barcode,
                    itemNumber,
                    productName,
                    secondProductName,
                    purchasePrice,
                    retailPrice,
                    oldPurchasePrice,
                    oldRetailPrice,
                    supplierId,
                    categoryId,
                    stockQuantity
                ) VALUES (
                    1,
                    '8050000000012',
                    'ITM-001',
                    'Whole Milk',
                    'Bottle',
                    1.50,
                    2.50,
                    1.20,
                    2.20,
                    10,
                    20,
                    7.0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO product_prices(id, productId, type, price, effectiveAt, source, note, createdAt)
                VALUES
                    (1, 1, 'PURCHASE', 1.20, '2026-03-01 10:00:00', 'IMPORT_PREV', NULL, '2026-03-01 10:00:00'),
                    (2, 1, 'PURCHASE', 1.50, '2026-03-02 10:00:00', 'IMPORT', NULL, '2026-03-02 10:00:00'),
                    (3, 1, 'RETAIL', 2.20, '2026-03-01 10:00:00', 'IMPORT_PREV', NULL, '2026-03-01 10:00:00'),
                    (4, 1, 'RETAIL', 2.50, '2026-03-02 10:00:00', 'IMPORT', NULL, '2026-03-02 10:00:00')
                """.trimIndent()
            )
        }

        val migrated = openMigratedDatabase(migratedName)
        val fresh = openFreshDatabase(freshName)

        assertEquals("11", querySingleValue(migrated, "PRAGMA user_version"))

        val product = migrated.productDao().findByBarcode("8050000000012")
        assertNotNull(product)
        assertEquals("ITM-001", product!!.itemNumber)
        assertEquals("Whole Milk", product.productName)
        assertEquals("Bottle", product.secondProductName)
        assertEquals(1.50, product.purchasePrice!!, 0.0001)
        assertEquals(2.50, product.retailPrice!!, 0.0001)
        assertEquals(1.20, product.oldPurchasePrice!!, 0.0001)
        assertEquals(2.20, product.oldRetailPrice!!, 0.0001)
        assertEquals(10L, product.supplierId)
        assertEquals(20L, product.categoryId)
        assertEquals(7.0, product.stockQuantity!!, 0.0001)

        assertEquals(
            columnShape(fresh, "products"),
            columnShape(migrated, "products")
        )
        assertEquals(
            indexInfo(fresh, "products"),
            indexInfo(migrated, "products")
        )
        assertEquals(
            indexInfo(fresh, "product_prices"),
            indexInfo(migrated, "product_prices")
        )
        assertEquals(
            viewSql(fresh, "product_price_summary"),
            viewSql(migrated, "product_price_summary")
        )

        val viewRow = querySingleRow(
            migrated,
            """
            SELECT lastPurchase, prevPurchase, lastRetail, prevRetail
            FROM product_price_summary
            WHERE productId = 1
            """.trimIndent()
        )
        assertEquals(1.5, viewRow["lastPurchase"]!!.toDouble(), 0.0001)
        assertEquals(1.2, viewRow["prevPurchase"]!!.toDouble(), 0.0001)
        assertEquals(2.5, viewRow["lastRetail"]!!.toDouble(), 0.0001)
        assertEquals(2.2, viewRow["prevRetail"]!!.toDouble(), 0.0001)

        assertTrue(queryCount(migrated, "PRAGMA foreign_key_check") == 0)
        assertEquals("ok", querySingleValue(migrated, "PRAGMA integrity_check"))
        assertTrue(viewExists(migrated, "product_price_summary"))
    }

    @Test
    fun `migration 3 chain preserves history rows and aligns table name with fresh install at v7`() = runTest {
        val migratedName = "task009-migrated-v3-history.db"
        val freshName = "task009-fresh-v7-history.db"

        createLegacyDatabase(migratedName, version = 3) { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS suppliers(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_suppliers_name ON suppliers(name)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS products(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    barcode TEXT NOT NULL,
                    itemNumber TEXT,
                    productName TEXT,
                    secondProductName TEXT,
                    purchasePrice REAL,
                    retailPrice REAL,
                    oldPurchasePrice REAL,
                    oldRetailPrice REAL,
                    supplierId INTEGER,
                    stockQuantity REAL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_products_barcode ON products(barcode)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_products_supplierId ON products(supplierId)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS HistoryEntry(
                    uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    id TEXT NOT NULL,
                    timestamp TEXT NOT NULL,
                    data TEXT NOT NULL,
                    editable TEXT NOT NULL,
                    complete TEXT NOT NULL,
                    supplier TEXT NOT NULL,
                    wasExported INTEGER NOT NULL,
                    syncStatus TEXT NOT NULL,
                    orderTotal REAL NOT NULL,
                    paymentTotal REAL NOT NULL,
                    missingItems INTEGER NOT NULL,
                    totalItems INTEGER NOT NULL,
                    isManualEntry INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL("INSERT INTO suppliers(id, name) VALUES (11, 'History Supplier')")
            db.execSQL(
                """
                INSERT INTO products(
                    id,
                    barcode,
                    itemNumber,
                    productName,
                    secondProductName,
                    purchasePrice,
                    retailPrice,
                    oldPurchasePrice,
                    oldRetailPrice,
                    supplierId,
                    stockQuantity
                ) VALUES (
                    1,
                    '8050000000099',
                    'ITM-HIST',
                    'History Product',
                    'Legacy',
                    3.0,
                    4.5,
                    2.8,
                    4.2,
                    11,
                    2.0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO HistoryEntry(
                    uid,
                    id,
                    timestamp,
                    data,
                    editable,
                    complete,
                    supplier,
                    wasExported,
                    syncStatus,
                    orderTotal,
                    paymentTotal,
                    missingItems,
                    totalItems,
                    isManualEntry
                ) VALUES (
                    1,
                    'history-1',
                    '2026-03-01 09:30:00',
                    '[["barcode","qty"],["8050000000099","2"]]',
                    '[["",""],["",""]]',
                    '[false,true]',
                    'History Supplier',
                    0,
                    'NOT_ATTEMPTED',
                    12.0,
                    9.5,
                    1,
                    2,
                    0
                )
                """.trimIndent()
            )
        }

        val migrated = openMigratedDatabase(migratedName)
        val fresh = openFreshDatabase(freshName)

        assertFalse(tableExists(migrated, "HistoryEntry"))
        assertTrue(tableExists(migrated, "history_entries"))
        assertEquals(
            columnShape(fresh, "history_entries"),
            columnShape(migrated, "history_entries")
        )

        val historyRow = querySingleRow(
            migrated,
            """
            SELECT uid, id, timestamp, supplier, category, orderTotal, paymentTotal, missingItems, totalItems, isManualEntry
            FROM history_entries
            WHERE uid = 1
            """.trimIndent()
        )
        assertEquals("1", historyRow["uid"])
        assertEquals("history-1", historyRow["id"])
        assertEquals("2026-03-01 09:30:00", historyRow["timestamp"])
        assertEquals("History Supplier", historyRow["supplier"])
        assertEquals("", historyRow["category"])
        assertEquals(12.0, historyRow["orderTotal"]!!.toDouble(), 0.0001)
        assertEquals(9.5, historyRow["paymentTotal"]!!.toDouble(), 0.0001)
        assertEquals("1", historyRow["missingItems"])
        assertEquals("2", historyRow["totalItems"])
        assertEquals("0", historyRow["isManualEntry"])

        assertTrue(queryCount(migrated, "PRAGMA foreign_key_check") == 0)
        assertEquals("ok", querySingleValue(migrated, "PRAGMA integrity_check"))
    }

    @Test
    fun `migration 6 to 7 upgrades released v6 schema without identity hash crash`() = runTest {
        val migratedName = "task009-migrated-v6-to-v7.db"
        val freshName = "task009-fresh-v7-from-v6.db"

        createLegacyDatabase(migratedName, version = 6) { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS suppliers(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_suppliers_name ON suppliers(name)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS categories(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL COLLATE NOCASE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_categories_name ON categories(name)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS history_entries(
                    uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    id TEXT NOT NULL,
                    timestamp TEXT NOT NULL,
                    data TEXT NOT NULL,
                    editable TEXT NOT NULL,
                    complete TEXT NOT NULL,
                    supplier TEXT NOT NULL,
                    category TEXT NOT NULL,
                    wasExported INTEGER NOT NULL,
                    syncStatus TEXT NOT NULL,
                    orderTotal REAL NOT NULL,
                    paymentTotal REAL NOT NULL,
                    missingItems INTEGER NOT NULL,
                    totalItems INTEGER NOT NULL,
                    isManualEntry INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS products(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    barcode TEXT NOT NULL,
                    itemNumber TEXT,
                    productName TEXT,
                    secondProductName TEXT,
                    purchasePrice REAL,
                    retailPrice REAL,
                    oldPurchasePrice REAL,
                    oldRetailPrice REAL,
                    supplierId INTEGER,
                    categoryId INTEGER,
                    stockQuantity REAL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_products_barcode ON products(barcode)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_products_supplierId ON products(supplierId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_products_categoryId ON products(categoryId)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS product_prices(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    productId INTEGER NOT NULL,
                    type TEXT NOT NULL,
                    price REAL NOT NULL,
                    effectiveAt TEXT NOT NULL,
                    source TEXT,
                    note TEXT,
                    createdAt TEXT NOT NULL,
                    FOREIGN KEY(productId) REFERENCES products(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_product_prices_productId_type_effectiveAt ON product_prices(productId,type,effectiveAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_product_prices_productId_type_createdAt ON product_prices(productId,type,createdAt)")
            db.execSQL("CREATE VIEW `product_price_summary` AS $PRODUCT_PRICE_SUMMARY_QUERY")
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL(
                """
                INSERT OR REPLACE INTO room_master_table(id, identity_hash)
                VALUES (42, '$RELEASED_V6_IDENTITY_HASH')
                """.trimIndent()
            )

            db.execSQL("INSERT INTO suppliers(id, name) VALUES (12, 'Released Supplier')")
            db.execSQL("INSERT INTO categories(id, name) VALUES (21, 'Released Category')")
            db.execSQL(
                """
                INSERT INTO products(
                    id,
                    barcode,
                    itemNumber,
                    productName,
                    secondProductName,
                    purchasePrice,
                    retailPrice,
                    oldPurchasePrice,
                    oldRetailPrice,
                    supplierId,
                    categoryId,
                    stockQuantity
                ) VALUES (
                    1,
                    '8050000000077',
                    'ITM-V6',
                    'Released Product',
                    'Legacy V6',
                    4.20,
                    6.80,
                    4.0,
                    6.4,
                    12,
                    21,
                    3.0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO product_prices(id, productId, type, price, effectiveAt, source, note, createdAt)
                VALUES
                    (1, 1, 'PURCHASE', 4.0, '2026-03-01 08:00:00', 'IMPORT_PREV', NULL, '2026-03-01 08:00:00'),
                    (2, 1, 'PURCHASE', 4.2, '2026-03-02 08:00:00', 'IMPORT', NULL, '2026-03-02 08:00:00'),
                    (3, 1, 'RETAIL', 6.4, '2026-03-01 08:00:00', 'IMPORT_PREV', NULL, '2026-03-01 08:00:00'),
                    (4, 1, 'RETAIL', 6.8, '2026-03-02 08:00:00', 'IMPORT', NULL, '2026-03-02 08:00:00')
                """.trimIndent()
            )
        }

        val migrated = openMigratedDatabase(migratedName)
        val fresh = openFreshDatabase(freshName)

        assertEquals("11", querySingleValue(migrated, "PRAGMA user_version"))

        val product = migrated.productDao().findByBarcode("8050000000077")
        assertNotNull(product)
        assertEquals("ITM-V6", product!!.itemNumber)
        assertEquals("Released Product", product.productName)
        assertEquals("Legacy V6", product.secondProductName)
        assertEquals(4.20, product.purchasePrice!!, 0.0001)
        assertEquals(6.80, product.retailPrice!!, 0.0001)
        assertEquals(4.0, product.oldPurchasePrice!!, 0.0001)
        assertEquals(6.4, product.oldRetailPrice!!, 0.0001)
        assertEquals(12L, product.supplierId)
        assertEquals(21L, product.categoryId)
        assertEquals(3.0, product.stockQuantity!!, 0.0001)

        assertEquals(columnShape(fresh, "products"), columnShape(migrated, "products"))
        assertEquals(indexInfo(fresh, "product_prices"), indexInfo(migrated, "product_prices"))
        assertEquals(viewSql(fresh, "product_price_summary"), viewSql(migrated, "product_price_summary"))

        val viewRow = querySingleRow(
            migrated,
            """
            SELECT lastPurchase, prevPurchase, lastRetail, prevRetail
            FROM product_price_summary
            WHERE productId = 1
            """.trimIndent()
        )
        assertEquals(4.2, viewRow["lastPurchase"]!!.toDouble(), 0.0001)
        assertEquals(4.0, viewRow["prevPurchase"]!!.toDouble(), 0.0001)
        assertEquals(6.8, viewRow["lastRetail"]!!.toDouble(), 0.0001)
        assertEquals(6.4, viewRow["prevRetail"]!!.toDouble(), 0.0001)

        assertTrue(queryCount(migrated, "PRAGMA foreign_key_check") == 0)
        assertEquals("ok", querySingleValue(migrated, "PRAGMA integrity_check"))
        assertTrue(viewExists(migrated, "product_price_summary"))
    }

    @Test
    fun `migration 7 chain to current schema preserves history_entry_remote_refs and existing data`() = runTest {
        val migratedName = "task007-migrated-v7-to-v8.db"
        val freshName = "task007-fresh-v8.db"

        createLegacyDatabase(migratedName, version = 7) { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS suppliers(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_suppliers_name ON suppliers(name)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS categories(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL COLLATE NOCASE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_categories_name ON categories(name)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS history_entries(
                    uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    id TEXT NOT NULL,
                    timestamp TEXT NOT NULL,
                    data TEXT NOT NULL,
                    editable TEXT NOT NULL,
                    complete TEXT NOT NULL,
                    supplier TEXT NOT NULL,
                    category TEXT NOT NULL,
                    wasExported INTEGER NOT NULL,
                    syncStatus TEXT NOT NULL,
                    orderTotal REAL NOT NULL,
                    paymentTotal REAL NOT NULL,
                    missingItems INTEGER NOT NULL,
                    totalItems INTEGER NOT NULL,
                    isManualEntry INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS products(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    barcode TEXT NOT NULL,
                    itemNumber TEXT,
                    productName TEXT,
                    secondProductName TEXT,
                    purchasePrice REAL,
                    retailPrice REAL,
                    oldPurchasePrice REAL,
                    oldRetailPrice REAL,
                    supplierId INTEGER,
                    categoryId INTEGER,
                    stockQuantity REAL,
                    FOREIGN KEY(supplierId) REFERENCES suppliers(id) ON DELETE SET NULL,
                    FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_products_barcode ON products(barcode)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_products_supplierId ON products(supplierId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_products_categoryId ON products(categoryId)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS product_prices(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    productId INTEGER NOT NULL,
                    type TEXT NOT NULL,
                    price REAL NOT NULL,
                    effectiveAt TEXT NOT NULL,
                    source TEXT,
                    note TEXT,
                    createdAt TEXT NOT NULL,
                    FOREIGN KEY(productId) REFERENCES products(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_product_prices_productId_type_effectiveAt ON product_prices(productId,type,effectiveAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_product_prices_productId_type_createdAt ON product_prices(productId,type,createdAt)")
            db.execSQL("CREATE VIEW `product_price_summary` AS $PRODUCT_PRICE_SUMMARY_QUERY")

            // Riga di history da preservare dopo la migrazione
            db.execSQL(
                """
                INSERT INTO history_entries(
                    uid, id, timestamp, data, editable, complete,
                    supplier, category, wasExported, syncStatus,
                    orderTotal, paymentTotal, missingItems, totalItems, isManualEntry
                ) VALUES (
                    42,
                    'session-007-pre',
                    '2026-04-15 09:00:00',
                    '[["barcode"],["8050000000042"]]',
                    '[["",""]]',
                    '[false]',
                    'Fornitore Test',
                    'Categoria Test',
                    0,
                    'NOT_ATTEMPTED',
                    10.0,
                    8.5,
                    0,
                    1,
                    0
                )
                """.trimIndent()
            )
        }

        val migrated = openMigratedDatabase(migratedName)
        val fresh = openFreshDatabase(freshName)

        // Versione aggiornata allo schema corrente (v11)
        assertEquals("11", querySingleValue(migrated, "PRAGMA user_version"))

        // La nuova tabella bridge è stata creata
        assertTrue(tableExists(migrated, "history_entry_remote_refs"))

        // Schema bridge uguale a fresh install
        assertEquals(
            columnShape(fresh, "history_entry_remote_refs"),
            columnShape(migrated, "history_entry_remote_refs")
        )
        assertEquals(
            indexInfo(fresh, "history_entry_remote_refs"),
            indexInfo(migrated, "history_entry_remote_refs")
        )

        // I dati preesistenti in history_entries sono intatti
        val historyRow = querySingleRow(
            migrated,
            "SELECT uid, id, supplier FROM history_entries WHERE uid = 42"
        )
        assertEquals("42", historyRow["uid"])
        assertEquals("session-007-pre", historyRow["id"])
        assertEquals("Fornitore Test", historyRow["supplier"])

        // La tabella bridge è inizialmente vuota (nessun remote_id auto-generato)
        assertEquals(0, queryCount(migrated, "SELECT COUNT(*) FROM history_entry_remote_refs"))

        // Integrità DB e FK
        assertTrue(queryCount(migrated, "PRAGMA foreign_key_check") == 0)
        assertEquals("ok", querySingleValue(migrated, "PRAGMA integrity_check"))
    }

    @Test
    fun `migration 7 to 8 directly creates bridge table with unique historyEntryUid and cascade FK`() = runTest {
        val migratedName = "task007-direct-v7-to-v8.db"

        createLegacyDatabase(migratedName, version = 7) { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS history_entries(
                    uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    id TEXT NOT NULL,
                    timestamp TEXT NOT NULL,
                    data TEXT NOT NULL,
                    editable TEXT NOT NULL,
                    complete TEXT NOT NULL,
                    supplier TEXT NOT NULL,
                    category TEXT NOT NULL,
                    wasExported INTEGER NOT NULL,
                    syncStatus TEXT NOT NULL,
                    orderTotal REAL NOT NULL,
                    paymentTotal REAL NOT NULL,
                    missingItems INTEGER NOT NULL,
                    totalItems INTEGER NOT NULL,
                    isManualEntry INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO history_entries(
                    uid, id, timestamp, data, editable, complete,
                    supplier, category, wasExported, syncStatus,
                    orderTotal, paymentTotal, missingItems, totalItems, isManualEntry
                ) VALUES (
                    42,
                    'session-007-direct',
                    '2026-04-15 09:00:00',
                    '[["barcode"],["8050000000042"]]',
                    '[["",""]]',
                    '[false]',
                    'Fornitore Test',
                    'Categoria Test',
                    0,
                    'NOT_ATTEMPTED',
                    10.0,
                    8.5,
                    0,
                    1,
                    0
                )
                """.trimIndent()
            )
        }

        val migrated = openSupportMigratedDatabase(
            name = migratedName,
            targetVersion = 8
        ) { db, oldVersion, newVersion ->
            assertEquals(7, oldVersion)
            assertEquals(8, newVersion)
            AppDatabase.MIGRATION_7_8.migrate(db)
        }

        assertEquals(8, migrated.version)
        assertTrue(tableExists(migrated, "history_entry_remote_refs"))

        val bridgeIndexes = indexInfo(migrated, "history_entry_remote_refs")
        assertTrue(
            bridgeIndexes.any { it.columns == listOf("historyEntryUid") && it.unique }
        )
        assertFalse(
            bridgeIndexes.any { it.columns == listOf("remoteId") }
        )

        migrated.execSQL(
            """
            INSERT INTO history_entry_remote_refs(id, historyEntryUid, remoteId)
            VALUES (1, 42, 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee')
            """.trimIndent()
        )
        assertEquals(1, queryCount(migrated, "SELECT COUNT(*) FROM history_entry_remote_refs"))

        migrated.execSQL("DELETE FROM history_entries WHERE uid = 42")
        assertEquals(0, queryCount(migrated, "SELECT COUNT(*) FROM history_entry_remote_refs"))
        assertTrue(queryCount(migrated, "PRAGMA foreign_key_check") == 0)
        assertEquals("ok", querySingleValue(migrated, "PRAGMA integrity_check"))
    }

    @Test
    fun `migration 8 to 9 adds unique remoteId index and preserves existing bridge rows`() = runTest {
        val migratedName = "task008-migrated-v8-to-v9.db"
        val freshName = "task008-fresh-v9.db"

        createLegacyDatabase(migratedName, version = 8) { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS suppliers(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_suppliers_name ON suppliers(name)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS categories(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL COLLATE NOCASE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_categories_name ON categories(name)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS history_entries(
                    uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    id TEXT NOT NULL,
                    timestamp TEXT NOT NULL,
                    data TEXT NOT NULL,
                    editable TEXT NOT NULL,
                    complete TEXT NOT NULL,
                    supplier TEXT NOT NULL,
                    category TEXT NOT NULL,
                    wasExported INTEGER NOT NULL,
                    syncStatus TEXT NOT NULL,
                    orderTotal REAL NOT NULL,
                    paymentTotal REAL NOT NULL,
                    missingItems INTEGER NOT NULL,
                    totalItems INTEGER NOT NULL,
                    isManualEntry INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS products(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    barcode TEXT NOT NULL,
                    itemNumber TEXT,
                    productName TEXT,
                    secondProductName TEXT,
                    purchasePrice REAL,
                    retailPrice REAL,
                    oldPurchasePrice REAL,
                    oldRetailPrice REAL,
                    supplierId INTEGER,
                    categoryId INTEGER,
                    stockQuantity REAL,
                    FOREIGN KEY(supplierId) REFERENCES suppliers(id) ON DELETE SET NULL,
                    FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_products_barcode ON products(barcode)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_products_supplierId ON products(supplierId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_products_categoryId ON products(categoryId)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS product_prices(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    productId INTEGER NOT NULL,
                    type TEXT NOT NULL,
                    price REAL NOT NULL,
                    effectiveAt TEXT NOT NULL,
                    source TEXT,
                    note TEXT,
                    createdAt TEXT NOT NULL,
                    FOREIGN KEY(productId) REFERENCES products(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_product_prices_productId_type_effectiveAt ON product_prices(productId,type,effectiveAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_product_prices_productId_type_createdAt ON product_prices(productId,type,createdAt)")
            db.execSQL("CREATE VIEW `product_price_summary` AS $PRODUCT_PRICE_SUMMARY_QUERY")
            // Tabella bridge già presente (v8)
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `history_entry_remote_refs` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `historyEntryUid` INTEGER NOT NULL,
                    `remoteId` TEXT NOT NULL,
                    FOREIGN KEY(`historyEntryUid`) REFERENCES `history_entries`(`uid`) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_history_entry_remote_refs_historyEntryUid` ON `history_entry_remote_refs` (`historyEntryUid`)")
            // Riga di history e bridge preesistente da preservare
            db.execSQL(
                """
                INSERT INTO history_entries(
                    uid, id, timestamp, data, editable, complete,
                    supplier, category, wasExported, syncStatus,
                    orderTotal, paymentTotal, missingItems, totalItems, isManualEntry
                ) VALUES (
                    10,
                    'session-008-pre',
                    '2026-04-15 09:00:00',
                    '[["barcode"],["999"]]',
                    '[["",""]]',
                    '[false]',
                    'Fornitore 008',
                    'Cat 008',
                    0,
                    'NOT_ATTEMPTED',
                    5.0, 4.0, 0, 1, 0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `history_entry_remote_refs`(id, historyEntryUid, remoteId)
                VALUES (1, 10, 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee')
                """.trimIndent()
            )
        }

        val migrated = openMigratedDatabase(migratedName)
        val fresh = openFreshDatabase(freshName)

        // Versione aggiornata allo schema Room corrente (v11; include 8→9 …10→11)
        assertEquals("11", querySingleValue(migrated, "PRAGMA user_version"))

        // L'indice unico su remoteId ora esiste
        val bridgeIndexes = indexInfo(migrated, "history_entry_remote_refs")
        val remoteIdIndex = bridgeIndexes.find { it.columns == listOf("remoteId") }
        assertNotNull(remoteIdIndex)
        assertTrue(remoteIdIndex!!.unique)

        // Schema bridge allineato con fresh install (schema Room corrente)
        assertEquals(
            indexInfo(fresh, "history_entry_remote_refs"),
            indexInfo(migrated, "history_entry_remote_refs")
        )

        // La riga bridge preesistente è intatta
        val bridgeRow = querySingleRow(
            migrated,
            "SELECT historyEntryUid, remoteId FROM history_entry_remote_refs WHERE id = 1"
        )
        assertEquals("10", bridgeRow["historyEntryUid"])
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", bridgeRow["remoteId"])

        // I dati history preesistenti sono intatti
        val historyRow = querySingleRow(
            migrated,
            "SELECT uid, id, supplier FROM history_entries WHERE uid = 10"
        )
        assertEquals("10", historyRow["uid"])
        assertEquals("session-008-pre", historyRow["id"])
        assertEquals("Fornitore 008", historyRow["supplier"])

        // Integrità DB
        assertTrue(queryCount(migrated, "PRAGMA foreign_key_check") == 0)
        assertEquals("ok", querySingleValue(migrated, "PRAGMA integrity_check"))
    }

    private fun createLegacyDatabase(
        name: String,
        version: Int,
        seed: (SQLiteDatabase) -> Unit
    ) {
        resetDatabase(name)
        val dbFile = app.getDatabasePath(name)
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            seed(db)
            db.version = version
        }
    }

    @Test
    fun `migration 9 to 10 adds sync state columns to bridge and preserves existing rows`() = runTest {
        val migratedName = "task009-migrated-v9-to-v10.db"
        val freshName = "task009-fresh-v10.db"

        createLegacyDatabase(migratedName, version = 9) { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS suppliers(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_suppliers_name ON suppliers(name)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS categories(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL COLLATE NOCASE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_categories_name ON categories(name)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS history_entries(
                    uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    id TEXT NOT NULL,
                    timestamp TEXT NOT NULL,
                    data TEXT NOT NULL,
                    editable TEXT NOT NULL,
                    complete TEXT NOT NULL,
                    supplier TEXT NOT NULL,
                    category TEXT NOT NULL,
                    wasExported INTEGER NOT NULL,
                    syncStatus TEXT NOT NULL,
                    orderTotal REAL NOT NULL,
                    paymentTotal REAL NOT NULL,
                    missingItems INTEGER NOT NULL,
                    totalItems INTEGER NOT NULL,
                    isManualEntry INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS products(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    barcode TEXT NOT NULL,
                    itemNumber TEXT,
                    productName TEXT,
                    secondProductName TEXT,
                    purchasePrice REAL,
                    retailPrice REAL,
                    oldPurchasePrice REAL,
                    oldRetailPrice REAL,
                    supplierId INTEGER,
                    categoryId INTEGER,
                    stockQuantity REAL,
                    FOREIGN KEY(supplierId) REFERENCES suppliers(id) ON DELETE SET NULL,
                    FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_products_barcode ON products(barcode)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_products_supplierId ON products(supplierId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_products_categoryId ON products(categoryId)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS product_prices(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    productId INTEGER NOT NULL,
                    type TEXT NOT NULL,
                    price REAL NOT NULL,
                    effectiveAt TEXT NOT NULL,
                    source TEXT,
                    note TEXT,
                    createdAt TEXT NOT NULL,
                    FOREIGN KEY(productId) REFERENCES products(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_product_prices_productId_type_effectiveAt ON product_prices(productId,type,effectiveAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_product_prices_productId_type_createdAt ON product_prices(productId,type,createdAt)")
            db.execSQL("CREATE VIEW `product_price_summary` AS $PRODUCT_PRICE_SUMMARY_QUERY")
            // Bridge v9: solo id, historyEntryUid, remoteId
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `history_entry_remote_refs` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `historyEntryUid` INTEGER NOT NULL,
                    `remoteId` TEXT NOT NULL,
                    FOREIGN KEY(`historyEntryUid`) REFERENCES `history_entries`(`uid`) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_history_entry_remote_refs_historyEntryUid` ON `history_entry_remote_refs` (`historyEntryUid`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_history_entry_remote_refs_remoteId` ON `history_entry_remote_refs` (`remoteId`)")

            // Riga history + bridge preesistente da preservare
            db.execSQL(
                """
                INSERT INTO history_entries(
                    uid, id, timestamp, data, editable, complete,
                    supplier, category, wasExported, syncStatus,
                    orderTotal, paymentTotal, missingItems, totalItems, isManualEntry
                ) VALUES (
                    5,
                    'session-009-pre',
                    '2026-04-15 09:00:00',
                    '[["barcode"],["8050000000005"]]',
                    '[["",""]]',
                    '[false]',
                    'Fornitore 009',
                    'Cat 009',
                    0,
                    'NOT_ATTEMPTED',
                    7.0, 5.5, 0, 1, 0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO history_entry_remote_refs(id, historyEntryUid, remoteId)
                VALUES (1, 5, 'bbbbbbbb-cccc-dddd-eeee-ffffffffffff')
                """.trimIndent()
            )
        }

        val migrated = openMigratedDatabase(migratedName)
        val fresh = openFreshDatabase(freshName)

        // Versione aggiornata allo schema Room corrente (v11)
        assertEquals("11", querySingleValue(migrated, "PRAGMA user_version"))

        // Schema bridge allineato con fresh install (schema Room corrente)
        assertEquals(
            columnShape(fresh, "history_entry_remote_refs"),
            columnShape(migrated, "history_entry_remote_refs")
        )

        // Riga bridge preesistente: nuove colonne con valori default
        val bridgeRow = querySingleRow(
            migrated,
            "SELECT historyEntryUid, remoteId, localChangeRevision, lastSyncedLocalRevision, lastRemoteAppliedAt, lastRemotePayloadFingerprint FROM history_entry_remote_refs WHERE id = 1"
        )
        assertEquals("5", bridgeRow["historyEntryUid"])
        assertEquals("bbbbbbbb-cccc-dddd-eeee-ffffffffffff", bridgeRow["remoteId"])
        // Colonne aggiunte dalla migrazione: default 0 per le Integer, null (→ "") per le nullable
        assertEquals("0", bridgeRow["localChangeRevision"])
        assertEquals("0", bridgeRow["lastSyncedLocalRevision"])
        assertEquals("", bridgeRow["lastRemoteAppliedAt"])          // null → ""
        assertEquals("", bridgeRow["lastRemotePayloadFingerprint"]) // null → ""

        // Dati history preesistenti intatti
        val historyRow = querySingleRow(
            migrated,
            "SELECT uid, id, supplier FROM history_entries WHERE uid = 5"
        )
        assertEquals("5", historyRow["uid"])
        assertEquals("session-009-pre", historyRow["id"])
        assertEquals("Fornitore 009", historyRow["supplier"])

        // Integrità DB
        assertTrue(queryCount(migrated, "PRAGMA foreign_key_check") == 0)
        assertEquals("ok", querySingleValue(migrated, "PRAGMA integrity_check"))
    }

    @Test
    fun `migration 10 to 11 adds catalog bridge tables`() = runTest {
        val dbName = "task013-migrate-10-to-11.db"
        createLegacyDatabase(dbName, version = 10) { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS suppliers(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_suppliers_name ON suppliers(name)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS categories(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL COLLATE NOCASE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_categories_name ON categories(name)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS history_entries(
                    uid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    id TEXT NOT NULL,
                    timestamp TEXT NOT NULL,
                    data TEXT NOT NULL,
                    editable TEXT NOT NULL,
                    complete TEXT NOT NULL,
                    supplier TEXT NOT NULL,
                    category TEXT NOT NULL,
                    wasExported INTEGER NOT NULL,
                    syncStatus TEXT NOT NULL,
                    orderTotal REAL NOT NULL,
                    paymentTotal REAL NOT NULL,
                    missingItems INTEGER NOT NULL,
                    totalItems INTEGER NOT NULL,
                    isManualEntry INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS products(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    barcode TEXT NOT NULL,
                    itemNumber TEXT,
                    productName TEXT,
                    secondProductName TEXT,
                    purchasePrice REAL,
                    retailPrice REAL,
                    oldPurchasePrice REAL,
                    oldRetailPrice REAL,
                    supplierId INTEGER,
                    categoryId INTEGER,
                    stockQuantity REAL,
                    FOREIGN KEY(supplierId) REFERENCES suppliers(id) ON DELETE SET NULL,
                    FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_products_barcode ON products(barcode)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_products_supplierId ON products(supplierId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_products_categoryId ON products(categoryId)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS product_prices(
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    productId INTEGER NOT NULL,
                    type TEXT NOT NULL,
                    price REAL NOT NULL,
                    effectiveAt TEXT NOT NULL,
                    source TEXT,
                    note TEXT,
                    createdAt TEXT NOT NULL,
                    FOREIGN KEY(productId) REFERENCES products(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_product_prices_productId_type_effectiveAt ON product_prices(productId,type,effectiveAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_product_prices_productId_type_createdAt ON product_prices(productId,type,createdAt)")
            db.execSQL("CREATE VIEW `product_price_summary` AS $PRODUCT_PRICE_SUMMARY_QUERY")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `history_entry_remote_refs` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `historyEntryUid` INTEGER NOT NULL,
                    `remoteId` TEXT NOT NULL,
                    `localChangeRevision` INTEGER NOT NULL DEFAULT 0,
                    `lastSyncedLocalRevision` INTEGER NOT NULL DEFAULT 0,
                    `lastRemoteAppliedAt` INTEGER,
                    `lastRemotePayloadFingerprint` TEXT,
                    FOREIGN KEY(`historyEntryUid`) REFERENCES `history_entries`(`uid`) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_history_entry_remote_refs_historyEntryUid` ON `history_entry_remote_refs` (`historyEntryUid`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_history_entry_remote_refs_remoteId` ON `history_entry_remote_refs` (`remoteId`)")
        }

        val migrated = openSupportMigratedDatabase(dbName, targetVersion = 11) { database, oldVersion, newVersion ->
            assertEquals(10, oldVersion)
            assertEquals(11, newVersion)
            AppDatabase.MIGRATION_10_11.migrate(database)
        }
        assertEquals("11", querySingleValue(migrated, "PRAGMA user_version"))
        assertTrue(tableExists(migrated, "supplier_remote_refs"))
        assertTrue(tableExists(migrated, "category_remote_refs"))
        assertTrue(tableExists(migrated, "product_remote_refs"))
        assertEquals(0, queryCount(migrated, "PRAGMA foreign_key_check"))
        migrated.close()
    }

    private fun openMigratedDatabase(name: String): AppDatabase =
        openDatabase(name) {
            addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11
            )
        }

    private fun openSupportMigratedDatabase(
        name: String,
        targetVersion: Int,
        onUpgrade: (SupportSQLiteDatabase, Int, Int) -> Unit
    ): SupportSQLiteDatabase {
        trackDatabase(name)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(app)
            .name(name)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(targetVersion) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit

                    override fun onConfigure(db: SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) {
                        onUpgrade(db, oldVersion, newVersion)
                    }
                }
            )
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        openedSupportHelpers += helper
        return helper.writableDatabase
    }

    private fun openFreshDatabase(name: String): AppDatabase =
        openDatabase(name, resetBeforeOpen = true) {}

    private fun openDatabase(
        name: String,
        resetBeforeOpen: Boolean = false,
        configure: androidx.room.RoomDatabase.Builder<AppDatabase>.() -> Unit
    ): AppDatabase {
        if (resetBeforeOpen) {
            resetDatabase(name)
        } else {
            trackDatabase(name)
        }
        val db = Room.databaseBuilder(app, AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .apply(configure)
            .build()
        openedDatabases += db
        db.openHelper.writableDatabase
        return db
    }

    private fun resetDatabase(name: String) {
        trackDatabase(name)
        app.deleteDatabase(name)
        deleteSidecar("${name}-wal")
        deleteSidecar("${name}-shm")
    }

    private fun trackDatabase(name: String) {
        databaseNames += name
    }

    private fun deleteSidecar(fileName: String) {
        val file = File(app.getDatabasePath(fileName).path)
        if (file.exists()) {
            file.delete()
        }
    }

    private fun tableExists(db: AppDatabase, table: String): Boolean =
        queryCount(
            db,
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'"
        ) == 1

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        queryCount(
            db,
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'"
        ) == 1

    private fun viewExists(db: AppDatabase, view: String): Boolean =
        queryCount(
            db,
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'view' AND name = '$view'"
        ) == 1

    private fun viewSql(db: AppDatabase, view: String): String =
        querySingleValue(
            db,
            "SELECT sql FROM sqlite_master WHERE type = 'view' AND name = '$view'"
        )

    private fun queryCount(db: AppDatabase, sql: String): Int =
        db.openHelper.writableDatabase.query(sql).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun queryCount(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun querySingleValue(db: AppDatabase, sql: String): String =
        db.openHelper.writableDatabase.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }

    private fun querySingleValue(db: SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }

    private fun querySingleRow(db: AppDatabase, sql: String): Map<String, String> =
        db.openHelper.writableDatabase.query(sql).use { cursor ->
            cursor.moveToFirst()
            buildMap {
                repeat(cursor.columnCount) { index ->
                    put(cursor.getColumnName(index), cursor.getString(index) ?: "")
                }
            }
        }

    private fun tableInfo(db: AppDatabase, table: String): List<ColumnSnapshot> =
        db.openHelper.writableDatabase.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ColumnSnapshot(
                            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                            type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                            notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1,
                            defaultValue = cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")),
                            pk = cursor.getInt(cursor.getColumnIndexOrThrow("pk"))
                        )
                    )
                }
            }
        }

    private fun columnShape(db: AppDatabase, table: String): List<ColumnShape> =
        tableInfo(db, table)
            .map { ColumnShape(name = it.name, type = it.type, notNull = it.notNull, pk = it.pk) }
            .sortedBy { it.name }

    private fun indexInfo(db: AppDatabase, table: String): List<IndexSnapshot> =
        db.openHelper.writableDatabase.query("PRAGMA index_list(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val indexName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    add(
                        IndexSnapshot(
                            name = indexName,
                            unique = cursor.getInt(cursor.getColumnIndexOrThrow("unique")) == 1,
                            columns = db.openHelper.writableDatabase
                                .query("PRAGMA index_info(`$indexName`)")
                                .use { indexCursor ->
                                    buildList {
                                        while (indexCursor.moveToNext()) {
                                            add(indexCursor.getString(indexCursor.getColumnIndexOrThrow("name")))
                                        }
                                    }
                                }
                        )
                    )
                }
            }.sortedBy { it.name }
        }

    private fun indexInfo(db: SupportSQLiteDatabase, table: String): List<IndexSnapshot> =
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val indexName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    add(
                        IndexSnapshot(
                            name = indexName,
                            unique = cursor.getInt(cursor.getColumnIndexOrThrow("unique")) == 1,
                            columns = db.query("PRAGMA index_info(`$indexName`)").use { indexCursor ->
                                buildList {
                                    while (indexCursor.moveToNext()) {
                                        add(indexCursor.getString(indexCursor.getColumnIndexOrThrow("name")))
                                    }
                                }
                            }
                        )
                    )
                }
            }.sortedBy { it.name }
        }

    private data class ColumnSnapshot(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val pk: Int
    )

    private data class ColumnShape(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val pk: Int
    )

    private data class IndexSnapshot(
        val name: String,
        val unique: Boolean,
        val columns: List<String>
    )
}
