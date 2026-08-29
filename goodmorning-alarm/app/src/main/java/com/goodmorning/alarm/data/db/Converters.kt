package com.goodmorning.alarm.data.db

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Room 类型转换器：为未来扩展（如视频标签列表）提供 List<String> ↔ JSON 文本的映射。
 * 当前实体均为基本类型，注册此转换器以保证后续加字段无需迁移 converter。
 */
class Converters {

    private val json = Json { ignoreUnknownKeys = true }
    private val stringListSerializer = ListSerializer(String.serializer())

    @TypeConverter
    fun fromStringList(value: List<String>?): String =
        json.encodeToString(stringListSerializer, value.orEmpty())

    @TypeConverter
    fun toStringList(value: String?): List<String> =
        if (value.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString(stringListSerializer, value) }.getOrDefault(emptyList())
}
