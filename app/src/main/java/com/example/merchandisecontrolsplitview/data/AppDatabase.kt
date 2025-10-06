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
    entities = [Product::class, Supplier::class, Category::class, HistoryEntry::class, ProductPrice::class],
    views = [ProductPriceSummary::class],
    version = 6,
    exportSchema = true
)
@TypeConverters(HistoryEntryConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun supplierDao(): SupplierDao
    abstract fun historyEntryDao(): HistoryEntryDao
    abstract fun categoryDao(): CategoryDao
    abstract fun productPriceDao(): ProductPriceDao

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
            db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS idx_prices_unique ON product_prices(productId,type,effectiveAt)""")
            db.execSQL("""CREATE INDEX IF NOT EXISTS idx_prices_lookup ON product_prices(productId,type,createdAt)""")
        } }

        // 🚀 5 → 6: rimuovi le colonne old*, ricrea indici e crea la view
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1) Ricostruisci products senza le colonne old*
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS products_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        barcode TEXT NOT NULL,
                        itemNumber TEXT,
                        productName TEXT,
                        secondProductName TEXT,
                        purchasePrice REAL,
                        retailPrice REAL,
                        supplierId INTEGER,
                        categoryId INTEGER,
                        stockQuantity REAL,
                        FOREIGN KEY(supplierId) REFERENCES suppliers(id) ON DELETE SET NULL,
                        FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO products_new (id, barcode, itemNumber, productName, secondProductName, purchasePrice, retailPrice, supplierId, categoryId, stockQuantity)
                    SELECT id, barcode, itemNumber, productName, secondProductName, purchasePrice, retailPrice, supplierId, categoryId, stockQuantity
                    FROM products
                """.trimIndent())
                db.execSQL("DROP TABLE products")
                db.execSQL("ALTER TABLE products_new RENAME TO products")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_products_barcode ON products(barcode)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_products_supplierId ON products(supplierId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_products_categoryId ON products(categoryId)")

                // 2) Crea (o ricrea) la view dei riassunti prezzo
                db.execSQL("""
                    CREATE VIEW IF NOT EXISTS product_price_summary AS
                    SELECT 
                      p.id AS productId,
                      (SELECT price FROM product_prices pr WHERE pr.productId=p.id AND pr.type='PURCHASE' ORDER BY pr.effectiveAt DESC LIMIT 1) AS lastPurchase,
                      (SELECT price FROM product_prices pr WHERE pr.productId=p.id AND pr.type='PURCHASE' AND pr.effectiveAt < (SELECT MAX(effectiveAt) FROM product_prices pr3 WHERE pr3.productId=p.id AND pr3.type='PURCHASE') ORDER BY pr.effectiveAt DESC LIMIT 1) AS prevPurchase,
                      (SELECT price FROM product_prices pr WHERE pr.productId=p.id AND pr.type='RETAIL' ORDER BY pr.effectiveAt DESC LIMIT 1) AS lastRetail,
                      (SELECT price FROM product_prices pr WHERE pr.productId=p.id AND pr.type='RETAIL' AND pr.effectiveAt < (SELECT MAX(effectiveAt) FROM product_prices pr3 WHERE pr3.productId=p.id AND pr3.type='RETAIL') ORDER BY pr.effectiveAt DESC LIMIT 1) AS prevRetail
                    FROM products p
                """.trimIndent())
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
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6
                    )
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .build().also { INSTANCE = it }
            }
    }
}