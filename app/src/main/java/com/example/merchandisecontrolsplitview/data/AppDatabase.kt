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
@Database(entities = [Product::class, Supplier::class, Category::class, HistoryEntry::class], version = 4)
@TypeConverters(HistoryEntryConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun supplierDao(): SupplierDao
    abstract fun historyEntryDao(): HistoryEntryDao
    // 3. Aggiungi il nuovo DAO per le categorie
    abstract fun categoryDao(): CategoryDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        // Le migrazioni precedenti rimangono invariate.
        val MIGRATION_1_2 = object : Migration(1, 2) { /* ... */ }
        val MIGRATION_2_3 = object : Migration(2, 3) { /* ... */ }

        // 4. NUOVA MIGRAZIONE DA VERSIONE 3 A 4
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Step 1: Crea la nuova tabella per le categorie
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `categories` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
                        `name` TEXT NOT NULL COLLATE NOCASE
                    )
                """)
                // Crea un indice univoco sul nome della categoria
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name` ON `categories` (`name`)")

                // Step 2: Aggiungi la colonna categoryId alla tabella products.
                // Usiamo un nome temporaneo per la colonna per evitare conflitti con la vecchia 'category'.
                db.execSQL("ALTER TABLE products ADD COLUMN categoryId INTEGER")

                // Step 3: Aggiungi la colonna category alla tabella HistoryEntry
                db.execSQL("ALTER TABLE HistoryEntry ADD COLUMN category TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getDatabase(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database"
                )
                    // 5. Aggiungi la nuova migrazione alla fine della lista.
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { INSTANCE = it }
            }
    }
}