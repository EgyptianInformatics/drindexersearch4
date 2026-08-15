package android.database

import java.io.Closeable

interface Cursor : Closeable {
    fun moveToFirst(): Boolean
    fun moveToNext(): Boolean
    fun getInt(columnIndex: Int): Int
    fun getLong(columnIndex: Int): Long
    fun getDouble(columnIndex: Int): Double
    fun getString(columnIndex: Int): String?
    fun isNull(columnIndex: Int): Boolean
    fun getType(columnIndex: Int): Int

    companion object {
        const val FIELD_TYPE_NULL = 0
        const val FIELD_TYPE_INTEGER = 1
        const val FIELD_TYPE_FLOAT = 2
        const val FIELD_TYPE_STRING = 3
        const val FIELD_TYPE_BLOB = 4
    }
}
