package com.example.merchandisecontrolsplitview.data

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class HistoryEntryConverters {
    private val gson = Gson()

    // Converter per List<List<String>> (usato sia per 'data' che per 'editable')
    @TypeConverter
    fun fromStringToListOfLists(value: String?): List<List<String>> {
        if (value == null) return emptyList()
        val listType = object : TypeToken<List<List<String>>>() {}.type
        return gson.fromJson(value, listType)
    }

    @TypeConverter
    fun fromListOfListsToString(list: List<List<String>>): String {
        return gson.toJson(list)
    }

    // --- Le funzioni per List<String> sono state rimosse ---

    // Converter per List<Boolean> (usato per 'complete')
    @TypeConverter
    fun fromStringToListOfBoolean(value: String?): List<Boolean> {
        if (value == null) return emptyList()
        val listType = object : TypeToken<List<Boolean>>() {}.type
        return gson.fromJson(value, listType)
    }

    @TypeConverter
    fun fromListOfBooleanToString(list: List<Boolean>): String {
        return gson.toJson(list)
    }
}