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
        addSenderPhoneNumberColumnIfNeeded(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        createSmsHistoryTableIfNotExists(db)
        addSenderPhoneNumberColumnIfNeeded(db)
    }

    fun insertSmsHistory(
        senderPhoneNumber: String,
        phoneNumber: String,
        message: String,
        smsSuccess: Boolean,
        smsError: String?
    ): Long {
        val values = ContentValues().apply {
            put(COLUMN_CREATED_AT, System.currentTimeMillis())
            put(COLUMN_SENDER_PHONE_NUMBER, senderPhoneNumber)
            put(COLUMN_PHONE_NUMBER, phoneNumber)
            put(COLUMN_MESSAGE, message)
            put(COLUMN_SMS_SUCCESS, if (smsSuccess) 1 else 0)
            put(COLUMN_SMS_ERROR, smsError)
        }
        return writableDatabase.insert(TABLE_SMS_HISTORY, null, values)
    }

    fun getRecentSmsHistories(limit: Int = 100): List<SmsHistoryItem> {
        val query = """
            SELECT
                $COLUMN_CREATED_AT,
                $COLUMN_SENDER_PHONE_NUMBER,
                $COLUMN_PHONE_NUMBER,
                $COLUMN_MESSAGE,
                $COLUMN_SMS_SUCCESS,
                $COLUMN_SMS_ERROR
            FROM $TABLE_SMS_HISTORY
            ORDER BY $COLUMN_CREATED_AT DESC
            LIMIT ?
        """.trimIndent()

        return readableDatabase.rawQuery(query, arrayOf(limit.toString())).use { cursor ->
            val items = mutableListOf<SmsHistoryItem>()
            while (cursor.moveToNext()) {
                items += SmsHistoryItem(
                    createdAtMillis = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT)),
                    senderPhoneNumber = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SENDER_PHONE_NUMBER)),
                    phoneNumber = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PHONE_NUMBER)),
                    message = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_MESSAGE)),
                    smsSuccess = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_SMS_SUCCESS)) == 1,
                    smsError = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SMS_ERROR))
                )
            }
            items
        }
    }

    fun countSuccessfulSmsBetweenForSender(
        senderPhoneNumber: String,
        startMillisInclusive: Long,
        endMillisExclusive: Long
    ): Int {
        val query = """
            SELECT COUNT(*)
            FROM $TABLE_SMS_HISTORY
            WHERE $COLUMN_SMS_SUCCESS = 1
              AND $COLUMN_SENDER_PHONE_NUMBER = ?
              AND $COLUMN_CREATED_AT >= ?
              AND $COLUMN_CREATED_AT < ?
        """.trimIndent()

        return readableDatabase.rawQuery(
            query,
            arrayOf(senderPhoneNumber, startMillisInclusive.toString(), endMillisExclusive.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    companion object {
        private const val DATABASE_NAME = "sms_history.db"
        private const val DATABASE_VERSION = 3

        private const val TABLE_SMS_HISTORY = "sms_history"
        private const val COLUMN_ID = "id"
        private const val COLUMN_CREATED_AT = "created_at"
        private const val COLUMN_SENDER_PHONE_NUMBER = "sender_phone_number"
        private const val COLUMN_PHONE_NUMBER = "phone_number"
        private const val COLUMN_MESSAGE = "message"
        private const val COLUMN_SMS_SUCCESS = "sms_success"
        private const val COLUMN_SMS_ERROR = "sms_error"
    }

    private fun createSmsHistoryTableIfNotExists(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SMS_HISTORY (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_CREATED_AT INTEGER NOT NULL,
                $COLUMN_SENDER_PHONE_NUMBER TEXT,
                $COLUMN_PHONE_NUMBER TEXT NOT NULL,
                $COLUMN_MESSAGE TEXT NOT NULL,
                $COLUMN_SMS_SUCCESS INTEGER NOT NULL,
                $COLUMN_SMS_ERROR TEXT
            )
            """.trimIndent()
        )
    }

    private fun addSenderPhoneNumberColumnIfNeeded(db: SQLiteDatabase) {
        val hasColumn = db.rawQuery("PRAGMA table_info($TABLE_SMS_HISTORY)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == COLUMN_SENDER_PHONE_NUMBER) {
                    found = true
                    break
                }
            }
            found
        }
        if (!hasColumn) {
            db.execSQL(
                "ALTER TABLE $TABLE_SMS_HISTORY ADD COLUMN $COLUMN_SENDER_PHONE_NUMBER TEXT"
            )
        }
    }
}

data class SmsHistoryItem(
    val createdAtMillis: Long,
    val senderPhoneNumber: String?,
    val phoneNumber: String,
    val message: String,
    val smsSuccess: Boolean,
    val smsError: String?
)
