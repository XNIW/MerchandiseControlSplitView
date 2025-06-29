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

        // La tua migrazione 1->2 rimane invariata
        val MIGRATION_1_2 = object : Migration(1, 2) { /* ... */ }

        // La tua migrazione 2->3 rimane invariata
        val MIGRATION_2_3 = object : Migration(2, 3) { /* ... */ }

        // 2. AGGIUNGI LA NUOVA MIGRAZIONE 3->4
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN category TEXT")
                db.execSQL("ALTER TABLE products ADD COLUMN stockQuantity REAL DEFAULT 0.0")
            }
        }

        fun getDatabase(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database"
                )
                    // 3. AGGIUNGI la nuova migrazione alla lista
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { INSTANCE = it }
            }
    }
}