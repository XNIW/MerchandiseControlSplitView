package com.example.merchandisecontrolsplitview.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// 1. Aggiungi Category::class alla lista delle entità.
// 2. Incrementa la versione a 4.
@Database(
    entities = [
        Product::class,
        Supplier::class,
        Category::class,
        HistoryEntry::class,
        ProductPrice::class,
        HistoryEntryRemoteRef::class,
        SupplierRemoteRef::class,
        CategoryRemoteRef::class,
        ProductRemoteRef::class,
        ProductPriceRemoteRef::class
    ],
    views = [ProductPriceSummary::class],
    version = 12,
    exportSchema = true
)
@TypeConverters(HistoryEntryConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun supplierDao(): SupplierDao
    abstract fun historyEntryDao(): HistoryEntryDao
    abstract fun categoryDao(): CategoryDao
    abstract fun productPriceDao(): ProductPriceDao
    abstract fun historyEntryRemoteRefDao(): HistoryEntryRemoteRefDao
    abstract fun supplierRemoteRefDao(): SupplierRemoteRefDao
    abstract fun categoryRemoteRefDao(): CategoryRemoteRefDao
    abstract fun productRemoteRefDao(): ProductRemoteRefDao
    abstract fun productPriceRemoteRefDao(): ProductPriceRemoteRefDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) { override fun migrate(db: SupportSQLiteDatabase) {} }
        val MIGRATION_2_3 = object : Migration(2, 3) { override fun migrate(db: SupportSQLiteDatabase) {} }
        val MIGRATION_3_4 = object : Migration(3, 4) { override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL COLLATE NOCASE)""")
            db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name` ON `categories` (`name`)""")
            db.execSQL("ALTER TABLE products ADD COLUMN categoryId INTEGER")
            db.execSQL("ALTER TABLE HistoryEntry ADD COLUMN category TEXT NOT NULL DEFAULT ''")
        } }
        val MIGRATION_4_5 = object : Migration(4, 5) { override fun migrate(db: SupportSQLiteDatabase) {
            db.normalizeHistoryEntriesTableName()
            db.execSQL("""
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
            """.trimIndent())
            db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS index_product_prices_productId_type_effectiveAt ON product_prices(productId,type,effectiveAt)""")
            db.execSQL("""CREATE INDEX IF NOT EXISTS index_product_prices_productId_type_createdAt ON product_prices(productId,type,createdAt)""")
        } }

        // 5 → 6: ricrea products con FK/indici coerenti e mantiene i campi old* attesi da schema/entity
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.rebuildProductsToCurrentSchema()
                db.normalizeProductPricesIndices()
                db.recreateProductPriceSummaryView()
            }
        }

        // 6 → 7: formalizza lo schema Room aggiornato e riallinea i DB v6 esistenti senza fallback distruttivi
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.normalizeHistoryEntriesTableName()
                db.rebuildProductsToCurrentSchema()
                db.normalizeProductPricesIndices()
                db.recreateProductPriceSummaryView()
            }
        }

        // 7 → 8: aggiunge la tabella bridge locale per identità remota stabile (DEC-017 / task 007).
        // Non tocca history_entries né la navigation esistente.
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `history_entry_remote_refs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `historyEntryUid` INTEGER NOT NULL,
                        `remoteId` TEXT NOT NULL,
                        FOREIGN KEY(`historyEntryUid`) REFERENCES `history_entries`(`uid`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_history_entry_remote_refs_historyEntryUid`
                    ON `history_entry_remote_refs` (`historyEntryUid`)
                """.trimIndent())
            }
        }

        // 8 → 9: aggiunge indice unico su remoteId nel bridge per garantire dedup affidabile
        // a livello DB (task 008 / pull remoto controllato). Non tocca history_entries né navigation.
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_history_entry_remote_refs_remoteId`
                    ON `history_entry_remote_refs` (`remoteId`)
                """.trimIndent())
            }
        }

        // 9 → 10: aggiunge colonne sync state al bridge (task 009 / baseline conflitti).
        // Traccia revisione locale e stato ultimo apply remoto per state machine per-record.
        // Non tocca history_entries né la navigation esistente.
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history_entry_remote_refs ADD COLUMN localChangeRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE history_entry_remote_refs ADD COLUMN lastSyncedLocalRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE history_entry_remote_refs ADD COLUMN lastRemoteAppliedAt INTEGER")
                db.execSQL("ALTER TABLE history_entry_remote_refs ADD COLUMN lastRemotePayloadFingerprint TEXT")
            }
        }

        // 10 → 11: bridge catalogo (task 013 / DEC-020). Nessun cambio a entities core catalogo.
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `supplier_remote_refs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `supplierId` INTEGER NOT NULL,
                        `remoteId` TEXT NOT NULL,
                        `localChangeRevision` INTEGER NOT NULL DEFAULT 0,
                        `lastSyncedLocalRevision` INTEGER NOT NULL DEFAULT 0,
                        `lastRemoteAppliedAt` INTEGER,
                        `lastRemotePayloadFingerprint` TEXT,
                        FOREIGN KEY(`supplierId`) REFERENCES `suppliers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_supplier_remote_refs_supplierId`
                    ON `supplier_remote_refs` (`supplierId`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_supplier_remote_refs_remoteId`
                    ON `supplier_remote_refs` (`remoteId`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `category_remote_refs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `categoryId` INTEGER NOT NULL,
                        `remoteId` TEXT NOT NULL,
                        `localChangeRevision` INTEGER NOT NULL DEFAULT 0,
                        `lastSyncedLocalRevision` INTEGER NOT NULL DEFAULT 0,
                        `lastRemoteAppliedAt` INTEGER,
                        `lastRemotePayloadFingerprint` TEXT,
                        FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_category_remote_refs_categoryId`
                    ON `category_remote_refs` (`categoryId`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_category_remote_refs_remoteId`
                    ON `category_remote_refs` (`remoteId`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `product_remote_refs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `productId` INTEGER NOT NULL,
                        `remoteId` TEXT NOT NULL,
                        `localChangeRevision` INTEGER NOT NULL DEFAULT 0,
                        `lastSyncedLocalRevision` INTEGER NOT NULL DEFAULT 0,
                        `lastRemoteAppliedAt` INTEGER,
                        `lastRemotePayloadFingerprint` TEXT,
                        FOREIGN KEY(`productId`) REFERENCES `products`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_product_remote_refs_productId`
                    ON `product_remote_refs` (`productId`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_product_remote_refs_remoteId`
                    ON `product_remote_refs` (`remoteId`)
                    """.trimIndent()
                )
            }
        }

        // 11 → 12: bridge storico prezzi cloud (task 016 / DEC-021). Tabella vuota; nessun backfill dati.
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `product_price_remote_refs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `productPriceId` INTEGER NOT NULL,
                        `remoteId` TEXT NOT NULL,
                        FOREIGN KEY(`productPriceId`) REFERENCES `product_prices`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_product_price_remote_refs_productPriceId`
                    ON `product_price_remote_refs` (`productPriceId`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_product_price_remote_refs_remoteId`
                    ON `product_price_remote_refs` (`remoteId`)
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12
                    )
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .build().also { INSTANCE = it }
            }

        private fun SupportSQLiteDatabase.hasTable(name: String): Boolean =
            query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$name' LIMIT 1").use { cursor ->
                cursor.moveToFirst()
            }

        private fun SupportSQLiteDatabase.hasColumn(tableName: String, columnName: String): Boolean =
            query("PRAGMA table_info(`$tableName`)").use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == columnName) {
                        return@use true
                    }
                }
                false
            }

        private fun SupportSQLiteDatabase.normalizeHistoryEntriesTableName() {
            if (hasTable("HistoryEntry") && !hasTable("history_entries")) {
                execSQL("ALTER TABLE HistoryEntry RENAME TO history_entries")
            }
        }

        private fun SupportSQLiteDatabase.rebuildProductsToCurrentSchema() {
            val oldPurchasePriceSelect =
                if (hasColumn("products", "oldPurchasePrice")) "oldPurchasePrice" else "NULL"
            val oldRetailPriceSelect =
                if (hasColumn("products", "oldRetailPrice")) "oldRetailPrice" else "NULL"
            val categoryIdSelect =
                if (hasColumn("products", "categoryId")) "categoryId" else "NULL"

            execSQL("""
                CREATE TABLE IF NOT EXISTS products_new (
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
            """.trimIndent())
            execSQL("""
                INSERT INTO products_new (
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
                )
                SELECT
                    id,
                    barcode,
                    itemNumber,
                    productName,
                    secondProductName,
                    purchasePrice,
                    retailPrice,
                    $oldPurchasePriceSelect,
                    $oldRetailPriceSelect,
                    supplierId,
                    $categoryIdSelect,
                    stockQuantity
                FROM products
            """.trimIndent())
            execSQL("DROP TABLE products")
            execSQL("ALTER TABLE products_new RENAME TO products")
            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_products_barcode ON products(barcode)")
            execSQL("CREATE INDEX IF NOT EXISTS index_products_supplierId ON products(supplierId)")
            execSQL("CREATE INDEX IF NOT EXISTS index_products_categoryId ON products(categoryId)")
        }

        private fun SupportSQLiteDatabase.normalizeProductPricesIndices() {
            execSQL("DROP INDEX IF EXISTS idx_prices_unique")
            execSQL("DROP INDEX IF EXISTS idx_prices_lookup")
            execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_product_prices_productId_type_effectiveAt ON product_prices(productId,type,effectiveAt)")
            execSQL("CREATE INDEX IF NOT EXISTS index_product_prices_productId_type_createdAt ON product_prices(productId,type,createdAt)")
        }

        private fun SupportSQLiteDatabase.recreateProductPriceSummaryView() {
            execSQL("DROP VIEW IF EXISTS product_price_summary")
            execSQL("CREATE VIEW `product_price_summary` AS $PRODUCT_PRICE_SUMMARY_QUERY")
        }
    }
}
