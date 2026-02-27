package com.lgsextractor.data.local.database

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lgsextractor.domain.model.BoundingBox
import com.lgsextractor.domain.model.QuestionOption

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromBoundingBox(box: BoundingBox): String = gson.toJson(box)

    @TypeConverter
    fun toBoundingBox(json: String): BoundingBox = gson.fromJson(json, BoundingBox::class.java)

    @TypeConverter
    fun fromOptionList(options: List<QuestionOption>): String = gson.toJson(options)

    @TypeConverter
    fun toOptionList(json: String): List<QuestionOption> {
        val type = object : TypeToken<List<QuestionOption>>() {}.type
        return gson.fromJson(json, type)
    }

    @TypeConverter
    fun fromIntList(list: List<Int>): String = gson.toJson(list)

    @TypeConverter
    fun toIntList(json: String): List<Int> {
        val type = object : TypeToken<List<Int>>() {}.type
        return gson.fromJson(json, type)
    }
}
