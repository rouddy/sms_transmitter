package com.rouddy.smstransmitter.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.rouddy.smstransmitter.sms.SendSmsResult

class SmsHistoryDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        createSmsHistoryTableIfNotExists(db)
        addMissingColumns(db)
        createIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        createSmsHistoryTableIfNotExists(db)
        addMissingColumns(db)
        createIndexes(db)
    }

    /**
     * Inserts a history row. Pass [pendingParts] > 0 for a message handed to the radio whose
     * per-part sent results are still outstanding; see [recordSentPartResult].
     */
    fun insertSmsHistory(
        requestId: String?,
        senderPhoneNumber: String,
        phoneNumber: String,
        message: String,
        smsSuccess: Boolean,
        smsError: String?,
        pendingParts: Int = 0,
    ): Long {
        val values = ContentValues().apply {
            put(COLUMN_CREATED_AT, System.currentTimeMillis())
            put(COLUMN_REQUEST_ID, requestId)
            put(COLUMN_SENDER_PHONE_NUMBER, senderPhoneNumber)
            put(COLUMN_PHONE_NUMBER, phoneNumber)
            put(COLUMN_MESSAGE, message)
            put(COLUMN_SMS_SUCCESS, if (smsSuccess) 1 else 0)
            put(COLUMN_SMS_ERROR, smsError)
            put(COLUMN_PENDING_PARTS, pendingParts)
        }
        return writableDatabase.insert(TABLE_SMS_HISTORY, null, values)
    }

    /**
     * Records the sent result of one part of a pending message. The first part error is kept.
     * Returns the final result once the last outstanding part is recorded, otherwise null
     * (including for unknown or already finalized rows, so duplicate callbacks are ignored).
     */
    fun recordSentPartResult(historyId: Long, partError: String?): SendSmsResult? {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val (pendingParts, currentError) = db.rawQuery(
                "SELECT $COLUMN_PENDING_PARTS, $COLUMN_SMS_ERROR FROM $TABLE_SMS_HISTORY WHERE $COLUMN_ID = ?",
                arrayOf(historyId.toString())
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                cursor.getInt(0) to cursor.getString(1)
            }
            if (pendingParts <= 0) return null

            val remainingParts = pendingParts - 1
            val error = currentError ?: partError
            val values = ContentValues().apply {
                put(COLUMN_PENDING_PARTS, remainingParts)
                put(COLUMN_SMS_ERROR, error)
                if (remainingParts == 0) put(COLUMN_SMS_SUCCESS, if (error == null) 1 else 0)
            }
            db.update(TABLE_SMS_HISTORY, values, "$COLUMN_ID = ?", arrayOf(historyId.toString()))
            db.setTransactionSuccessful()
            return if (remainingParts == 0) SendSmsResult(success = error == null, error = error) else null
        } finally {
            db.endTransaction()
        }
    }

    /** Whether a message for [requestId] was already handled, so a duplicate FCM delivery can be skipped. */
    fun hasSmsHistoryForRequest(requestId: String): Boolean {
        return readableDatabase.rawQuery(
            "SELECT 1 FROM $TABLE_SMS_HISTORY WHERE $COLUMN_REQUEST_ID = ? LIMIT 1",
            arrayOf(requestId)
        ).use { cursor -> cursor.moveToFirst() }
    }

    /** Finalizes a pending message as failed, e.g. when handing it to the radio threw. */
    fun markSmsFailed(historyId: Long, smsError: String) {
        val values = ContentValues().apply {
            put(COLUMN_PENDING_PARTS, 0)
            put(COLUMN_SMS_SUCCESS, 0)
            put(COLUMN_SMS_ERROR, smsError)
        }
        writableDatabase.update(TABLE_SMS_HISTORY, values, "$COLUMN_ID = ?", arrayOf(historyId.toString()))
    }

    fun getRecentSmsHistories(limit: Int = 100): List<SmsHistoryItem> {
        val query = """
            SELECT
                $COLUMN_CREATED_AT,
                $COLUMN_SENDER_PHONE_NUMBER,
                $COLUMN_PHONE_NUMBER,
                $COLUMN_MESSAGE,
                $COLUMN_SMS_SUCCESS,
                $COLUMN_SMS_ERROR,
                $COLUMN_PENDING_PARTS
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
                    smsError = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SMS_ERROR)),
                    smsPending = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_PENDING_PARTS)) > 0,
                )
            }
            items
        }
    }

    /**
     * Counts successful messages for [senderPhoneNumber]. With [includePending], messages still
     * waiting for their sent result are counted too, so in-flight messages count toward limits.
     */
    fun countSmsBetweenForSender(
        senderPhoneNumber: String,
        startMillisInclusive: Long,
        endMillisExclusive: Long,
        includePending: Boolean,
    ): Int {
        val statusCondition = if (includePending) {
            "($COLUMN_SMS_SUCCESS = 1 OR $COLUMN_PENDING_PARTS > 0)"
        } else {
            "$COLUMN_SMS_SUCCESS = 1"
        }
        val query = """
            SELECT COUNT(*)
            FROM $TABLE_SMS_HISTORY
            WHERE $statusCondition
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
        private const val DATABASE_VERSION = 5

        private const val TABLE_SMS_HISTORY = "sms_history"
        private const val COLUMN_ID = "id"
        private const val COLUMN_CREATED_AT = "created_at"
        private const val COLUMN_SENDER_PHONE_NUMBER = "sender_phone_number"
        private const val COLUMN_PHONE_NUMBER = "phone_number"
        private const val COLUMN_MESSAGE = "message"
        private const val COLUMN_SMS_SUCCESS = "sms_success"
        private const val COLUMN_SMS_ERROR = "sms_error"
        private const val COLUMN_PENDING_PARTS = "pending_parts"
        private const val COLUMN_REQUEST_ID = "request_id"
        private const val INDEX_REQUEST_ID = "idx_sms_history_request_id"
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
                $COLUMN_SMS_ERROR TEXT,
                $COLUMN_PENDING_PARTS INTEGER NOT NULL DEFAULT 0,
                $COLUMN_REQUEST_ID TEXT
            )
            """.trimIndent()
        )
    }

    private fun addMissingColumns(db: SQLiteDatabase) {
        addColumnIfNeeded(db, COLUMN_SENDER_PHONE_NUMBER, "TEXT")
        addColumnIfNeeded(db, COLUMN_PENDING_PARTS, "INTEGER NOT NULL DEFAULT 0")
        addColumnIfNeeded(db, COLUMN_REQUEST_ID, "TEXT")
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS $INDEX_REQUEST_ID ON $TABLE_SMS_HISTORY ($COLUMN_REQUEST_ID)")
    }

    private fun addColumnIfNeeded(db: SQLiteDatabase, column: String, definition: String) {
        val hasColumn = db.rawQuery("PRAGMA table_info($TABLE_SMS_HISTORY)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) {
                    found = true
                    break
                }
            }
            found
        }
        if (!hasColumn) {
            db.execSQL("ALTER TABLE $TABLE_SMS_HISTORY ADD COLUMN $column $definition")
        }
    }
}

data class SmsHistoryItem(
    val createdAtMillis: Long,
    val senderPhoneNumber: String?,
    val phoneNumber: String,
    val message: String,
    val smsSuccess: Boolean,
    val smsError: String?,
    val smsPending: Boolean,
)
