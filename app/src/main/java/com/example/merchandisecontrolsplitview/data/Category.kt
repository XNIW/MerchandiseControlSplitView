package com.example.merchandisecontrolsplitview.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true)] // Indice per ricerche veloci e per evitare nomi duplicati
)
data class Category(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "name", collate = ColumnInfo.NOCASE)
    val name: String
)