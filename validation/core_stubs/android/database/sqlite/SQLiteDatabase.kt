package android.database.sqlite

import android.database.Cursor

open class SQLiteDatabase {
    open fun rawQuery(sql: String, selectionArgs: Array<String>?): Cursor =
        throw UnsupportedOperationException(sql)
}
