package com.rouddy.smstransmitter

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SmsHistoryDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        createSmsHistoryTableIfNotExists(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            addColumnIfMissing(db, TABLE_SMS_HISTORY, COLUMN_CALLBACK_ERROR, "TEXT")
        }
        createSmsHistoryTableIfNotExists(db)
    }

    fun insertSmsHistory(
        phoneNumber: String,
        message: String,
        smsSuccess: Boolean,
        smsError: String?,
        callbackUrl: String
    ): Long {
        val values = ContentValues().apply {
            put(COLUMN_CREATED_AT, System.currentTimeMillis())
            put(COLUMN_PHONE_NUMBER, phoneNumber)
            put(COLUMN_MESSAGE, message)
            put(COLUMN_SMS_SUCCESS, if (smsSuccess) 1 else 0)
            put(COLUMN_SMS_ERROR, smsError)
            put(COLUMN_CALLBACK_URL, callbackUrl)
        }
        return writableDatabase.insert(TABLE_SMS_HISTORY, null, values)
    }

    fun updateCallbackResult(rowId: Long, callbackSuccess: Boolean, callbackError: String?) {
        val values = ContentValues().apply {
            put(COLUMN_CALLBACK_SUCCESS, if (callbackSuccess) 1 else 0)
            put(COLUMN_CALLBACK_ERROR, callbackError)
        }
        writableDatabase.update(TABLE_SMS_HISTORY, values, "$COLUMN_ID = ?", arrayOf(rowId.toString()))
    }

    fun getRecentSmsHistories(limit: Int = 100): List<SmsHistoryItem> {
        val query = """
            SELECT
                $COLUMN_CREATED_AT,
                $COLUMN_PHONE_NUMBER,
                $COLUMN_MESSAGE,
                $COLUMN_SMS_SUCCESS,
                $COLUMN_SMS_ERROR,
                $COLUMN_CALLBACK_URL,
                $COLUMN_CALLBACK_SUCCESS,
                $COLUMN_CALLBACK_ERROR
            FROM $TABLE_SMS_HISTORY
            ORDER BY $COLUMN_CREATED_AT DESC
            LIMIT ?
        """.trimIndent()

        return readableDatabase.rawQuery(query, arrayOf(limit.toString())).use { cursor ->
            val items = mutableListOf<SmsHistoryItem>()
            while (cursor.moveToNext()) {
                val callbackSuccessIndex = cursor.getColumnIndexOrThrow(COLUMN_CALLBACK_SUCCESS)
                items += SmsHistoryItem(
                    createdAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT)),
                    phoneNumber = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PHONE_NUMBER)),
                    message = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MESSAGE)),
                    smsSuccess = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_SMS_SUCCESS)) == 1,
                    smsError = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SMS_ERROR)),
                    callbackUrl = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CALLBACK_URL)),
                    callbackSuccess = if (cursor.isNull(callbackSuccessIndex)) {
                        null
                    } else {
                        cursor.getInt(callbackSuccessIndex) == 1
                    },
                    callbackError = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CALLBACK_ERROR))
                )
            }
            items
        }
    }

    companion object {
        private const val DATABASE_NAME = "sms_history.db"
        private const val DATABASE_VERSION = 2

        private const val TABLE_SMS_HISTORY = "sms_history"
        private const val COLUMN_ID = "id"
        private const val COLUMN_CREATED_AT = "created_at"
        private const val COLUMN_PHONE_NUMBER = "phone_number"
        private const val COLUMN_MESSAGE = "message"
        private const val COLUMN_SMS_SUCCESS = "sms_success"
        private const val COLUMN_SMS_ERROR = "sms_error"
        private const val COLUMN_CALLBACK_URL = "callback_url"
        private const val COLUMN_CALLBACK_SUCCESS = "callback_success"
        private const val COLUMN_CALLBACK_ERROR = "callback_error"
    }

    private fun createSmsHistoryTableIfNotExists(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SMS_HISTORY (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_CREATED_AT INTEGER NOT NULL,
                $COLUMN_PHONE_NUMBER TEXT NOT NULL,
                $COLUMN_MESSAGE TEXT NOT NULL,
                $COLUMN_SMS_SUCCESS INTEGER NOT NULL,
                $COLUMN_SMS_ERROR TEXT,
                $COLUMN_CALLBACK_URL TEXT,
                $COLUMN_CALLBACK_SUCCESS INTEGER,
                $COLUMN_CALLBACK_ERROR TEXT
            )
            """.trimIndent()
        )
    }

    private fun addColumnIfMissing(
        db: SQLiteDatabase,
        tableName: String,
        columnName: String,
        columnType: String
    ) {
        val exists = db.rawQuery("PRAGMA table_info($tableName)", null).use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == columnName) {
                    found = true
                    break
                }
            }
            found
        }
        if (!exists) {
            db.execSQL("ALTER TABLE $tableName ADD COLUMN $columnName $columnType")
        }
    }
}

data class SmsHistoryItem(
    val createdAtMillis: Long,
    val phoneNumber: String,
    val message: String,
    val smsSuccess: Boolean,
    val smsError: String?,
    val callbackUrl: String?,
    val callbackSuccess: Boolean?,
    val callbackError: String?
)
