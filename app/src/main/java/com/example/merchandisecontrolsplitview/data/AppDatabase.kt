package com.example.merchandisecontrolsplitview.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// 1. AGGIUNGI HistoryEntry::class alla lista.
// 2. INCREMENTA la versione a 3.
@Database(entities = [Product::class, Supplier::class, HistoryEntry::class], version = 3)
@TypeConverters(HistoryEntryConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun supplierDao(): SupplierDao
    abstract fun historyEntryDao(): HistoryEntryDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        // Questa è la tua migrazione esistente da 1 a 2, la lasciamo com'è.
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `suppliers` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `name` TEXT NOT NULL
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_suppliers_name` ON `suppliers` (`name`)")
                db.execSQL("""
                    CREATE TABLE `products_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `barcode` TEXT NOT NULL, 
                        `itemNumber` TEXT, 
                        `productName` TEXT, 
                        `newPurchasePrice` REAL, 
                        `newRetailPrice` REAL, 
                        `oldPurchasePrice` REAL, 
                        `oldRetailPrice` REAL, 
                        `supplierId` INTEGER, 
                        FOREIGN KEY(`supplierId`) REFERENCES `suppliers`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                """)
                db.execSQL("""
                    INSERT INTO `products_new` (id, barcode, itemNumber, productName, newPurchasePrice, newRetailPrice, oldPurchasePrice, oldRetailPrice)
                    SELECT id, barcode, itemNumber, productName, newPurchasePrice, newRetailPrice, oldPurchasePrice, oldRetailPrice FROM products
                """)
                db.execSQL("DROP TABLE `products`")
                db.execSQL("ALTER TABLE `products_new` RENAME TO `products`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_products_barcode` ON `products` (`barcode`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_supplierId` ON `products` (`supplierId`)")
            }
        }

        // --- INIZIO NUOVO CODICE ---
        // 3. AGGIUNGIAMO la nuova migrazione da versione 2 a 3.
        //    Questo codice crea semplicemente la tabella HistoryEntry.
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `HistoryEntry` (
                        `uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `id` TEXT NOT NULL, 
                        `timestamp` TEXT NOT NULL, 
                        `data` TEXT NOT NULL, 
                        `editable` TEXT NOT NULL, 
                        `complete` TEXT NOT NULL, 
                        `supplier` TEXT NOT NULL, 
                        `wasExported` INTEGER NOT NULL, 
                        `syncStatus` TEXT NOT NULL
                    )
                """)
            }
        }
        // --- FINE NUOVO CODICE ---


        fun getDatabase(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database"
                )
                    // AGGIUNGI la nuova migrazione alla lista
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also { INSTANCE = it }
            }
    }
}