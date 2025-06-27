package com.example.merchandisecontrolsplitview.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "suppliers",
    indices = [Index(value = ["name"], unique = true)] // Il nome di ogni fornitore deve essere unico
)
data class Supplier(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String
)