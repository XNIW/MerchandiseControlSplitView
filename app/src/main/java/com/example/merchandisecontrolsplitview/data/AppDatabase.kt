package com.example.merchandisecontrolsplitview.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Product::class, Supplier::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun supplierDao(): SupplierDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        // --- INIZIO NUOVO CODICE PER LA MIGRAZIONE ---
        /**
         * Definisce la migrazione dalla versione 1 alla 2 del database.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Crea la nuova tabella 'suppliers'
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `suppliers` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `name` TEXT NOT NULL
                    )
                """)
                // Aggiungi l'indice univoco per il nome del fornitore
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_suppliers_name` ON `suppliers` (`name`)")

                // 2. Ricostruisce la tabella 'products' per aggiungere 'supplierId'
                //    e rimuovere la vecchia colonna 'supplier'.
                //    Questo processo è standard in SQLite per modifiche complesse.

                // Crea una tabella temporanea con la nuova struttura
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

                // Copia i dati dalla vecchia tabella alla nuova (la colonna 'supplier' viene persa)
                db.execSQL("""
                    INSERT INTO `products_new` (id, barcode, itemNumber, productName, newPurchasePrice, newRetailPrice, oldPurchasePrice, oldRetailPrice)
                    SELECT id, barcode, itemNumber, productName, newPurchasePrice, newRetailPrice, oldPurchasePrice, oldRetailPrice FROM products
                """)

                // Elimina la vecchia tabella
                db.execSQL("DROP TABLE `products`")

                // Rinomina la nuova tabella con il nome corretto
                db.execSQL("ALTER TABLE `products_new` RENAME TO `products`")

                // Ricrea gli indici necessari sulla nuova tabella 'products'
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_products_barcode` ON `products` (`barcode`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_products_supplierId` ON `products` (`supplierId`)")
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
                    // --- MODIFICA: Sostituisci il metodo deprecato con la migrazione ---
                    .addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }
    }
}