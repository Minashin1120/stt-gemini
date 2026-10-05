package com.minashin1120.voxcribe.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class HistoryRow(
    val id: Long,
    val actionType: String,
    val inputSummary: String,
    val thoughtText: String,
    val resultText: String,
    val timestampMs: Long,
)

/** Gemini Batch API ジョブ（Web版 BatchJob テーブルに相当）。status: running / succeeded / failed / cancelled / expired */
data class BatchRow(
    val id: Long,
    val providerJob: String,
    val model: String,
    val actionType: String,
    val inputSummary: String,
    val status: String,
    val thoughtText: String,
    val resultText: String,
    val error: String,
    val imported: Boolean,
    val createdMs: Long,
    val completedMs: Long,
)

data class WordSetRow(val id: Long, val name: String, val isActive: Boolean)

data class WordRow(val id: Long, val setId: Long, val reading: String, val replacement: String)

/**
 * Web版の MariaDB（History / WordSet / Word テーブル）に相当する端末内DB。
 */
class Db(context: Context) : SQLiteOpenHelper(context, "voxcribe.db", null, 2) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    private fun createBatchTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS batch_job (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                provider_job TEXT NOT NULL,
                model TEXT NOT NULL,
                action_type TEXT NOT NULL,
                input_summary TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'running',
                thought_text TEXT NOT NULL DEFAULT '',
                result_text TEXT NOT NULL DEFAULT '',
                error TEXT NOT NULL DEFAULT '',
                imported INTEGER NOT NULL DEFAULT 0,
                created INTEGER NOT NULL,
                completed INTEGER NOT NULL DEFAULT 0)"""
        )
    }

    override fun onCreate(db: SQLiteDatabase) {
        createBatchTable(db)
        db.execSQL(
            """CREATE TABLE history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                action_type TEXT NOT NULL,
                input_summary TEXT NOT NULL DEFAULT '',
                thought_text TEXT NOT NULL DEFAULT '',
                result_text TEXT NOT NULL DEFAULT '',
                timestamp INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE word_set (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE word (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                set_id INTEGER NOT NULL REFERENCES word_set(id) ON DELETE CASCADE,
                reading TEXT NOT NULL,
                replacement TEXT NOT NULL)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createBatchTable(db)
    }

    // ---------- History ----------

    @Synchronized
    fun insertHistory(action: String, input: String, thought: String, result: String): Long {
        val v = ContentValues().apply {
            put("action_type", action)
            put("input_summary", input)
            put("thought_text", thought)
            put("result_text", result)
            put("timestamp", System.currentTimeMillis())
        }
        return writableDatabase.insert("history", null, v)
    }

    @Synchronized
    fun latestHistory(): HistoryRow? =
        queryHistory("SELECT * FROM history ORDER BY timestamp DESC, id DESC LIMIT 1", emptyArray()).firstOrNull()

    @Synchronized
    fun historySince(sinceMs: Long, newestFirst: Boolean): List<HistoryRow> {
        val order = if (newestFirst) "DESC" else "ASC"
        return queryHistory(
            "SELECT * FROM history WHERE timestamp > ? ORDER BY timestamp $order, id $order",
            arrayOf(sinceMs.toString())
        )
    }

    @Synchronized
    fun updateHistoryResult(id: Long, result: String) {
        writableDatabase.update("history", ContentValues().apply { put("result_text", result) }, "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun deleteHistory(id: Long): Boolean =
        writableDatabase.delete("history", "id = ?", arrayOf(id.toString())) > 0

    @Synchronized
    fun clearHistory() {
        writableDatabase.delete("history", null, null)
    }

    @Synchronized
    fun deleteHistoryOlderThan(cutoffMs: Long) {
        writableDatabase.delete("history", "timestamp < ?", arrayOf(cutoffMs.toString()))
    }

    private fun queryHistory(sql: String, args: Array<String>): List<HistoryRow> {
        val out = ArrayList<HistoryRow>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out += HistoryRow(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    actionType = c.getString(c.getColumnIndexOrThrow("action_type")),
                    inputSummary = c.getString(c.getColumnIndexOrThrow("input_summary")),
                    thoughtText = c.getString(c.getColumnIndexOrThrow("thought_text")),
                    resultText = c.getString(c.getColumnIndexOrThrow("result_text")),
                    timestampMs = c.getLong(c.getColumnIndexOrThrow("timestamp")),
                )
            }
        }
        return out
    }

    // ---------- Batch jobs ----------

    @Synchronized
    fun insertBatch(providerJob: String, model: String, action: String, summary: String): Long =
        writableDatabase.insert("batch_job", null, ContentValues().apply {
            put("provider_job", providerJob)
            put("model", model)
            put("action_type", action)
            put("input_summary", summary)
            put("status", "running")
            put("created", System.currentTimeMillis())
        })

    @Synchronized
    fun batches(): List<BatchRow> = queryBatches("SELECT * FROM batch_job ORDER BY created DESC, id DESC", emptyArray())

    @Synchronized
    fun batch(id: Long): BatchRow? = queryBatches("SELECT * FROM batch_job WHERE id = ?", arrayOf(id.toString())).firstOrNull()

    @Synchronized
    fun finishBatch(id: Long, status: String, thought: String, result: String, error: String) {
        writableDatabase.update("batch_job", ContentValues().apply {
            put("status", status)
            put("thought_text", thought)
            put("result_text", result)
            put("error", error)
            put("completed", System.currentTimeMillis())
        }, "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun markBatchImported(id: Long) {
        writableDatabase.update("batch_job", ContentValues().apply { put("imported", 1) }, "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun deleteBatch(id: Long): Boolean =
        writableDatabase.delete("batch_job", "id = ?", arrayOf(id.toString())) > 0

    private fun queryBatches(sql: String, args: Array<String>): List<BatchRow> {
        val out = ArrayList<BatchRow>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out += BatchRow(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    providerJob = c.getString(c.getColumnIndexOrThrow("provider_job")),
                    model = c.getString(c.getColumnIndexOrThrow("model")),
                    actionType = c.getString(c.getColumnIndexOrThrow("action_type")),
                    inputSummary = c.getString(c.getColumnIndexOrThrow("input_summary")),
                    status = c.getString(c.getColumnIndexOrThrow("status")),
                    thoughtText = c.getString(c.getColumnIndexOrThrow("thought_text")),
                    resultText = c.getString(c.getColumnIndexOrThrow("result_text")),
                    error = c.getString(c.getColumnIndexOrThrow("error")),
                    imported = c.getInt(c.getColumnIndexOrThrow("imported")) != 0,
                    createdMs = c.getLong(c.getColumnIndexOrThrow("created")),
                    completedMs = c.getLong(c.getColumnIndexOrThrow("completed")),
                )
            }
        }
        return out
    }

    // ---------- Word sets ----------

    @Synchronized
    fun wordSets(): List<WordSetRow> {
        val out = ArrayList<WordSetRow>()
        readableDatabase.rawQuery("SELECT id, name, is_active FROM word_set ORDER BY id ASC", null).use { c ->
            while (c.moveToNext()) out += WordSetRow(c.getLong(0), c.getString(1), c.getInt(2) != 0)
        }
        return out
    }

    @Synchronized
    fun createWordSet(name: String): Long =
        writableDatabase.insert("word_set", null, ContentValues().apply {
            put("name", name)
            put("is_active", 0)
        })

    @Synchronized
    fun deleteWordSet(id: Long): Boolean =
        writableDatabase.delete("word_set", "id = ?", arrayOf(id.toString())) > 0

    @Synchronized
    fun toggleWordSet(id: Long): Boolean? {
        val current = wordSets().firstOrNull { it.id == id } ?: return null
        val next = !current.isActive
        writableDatabase.update("word_set", ContentValues().apply { put("is_active", if (next) 1 else 0) }, "id = ?", arrayOf(id.toString()))
        return next
    }

    @Synchronized
    fun resetWordSets() {
        writableDatabase.update("word_set", ContentValues().apply { put("is_active", 0) }, null, null)
    }

    @Synchronized
    fun words(setId: Long): List<WordRow> {
        val out = ArrayList<WordRow>()
        readableDatabase.rawQuery(
            "SELECT id, set_id, reading, replacement FROM word WHERE set_id = ? ORDER BY id ASC",
            arrayOf(setId.toString())
        ).use { c ->
            while (c.moveToNext()) out += WordRow(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3))
        }
        return out
    }

    @Synchronized
    fun addWord(setId: Long, reading: String, replacement: String): Long =
        writableDatabase.insert("word", null, ContentValues().apply {
            put("set_id", setId)
            put("reading", reading)
            put("replacement", replacement)
        })

    @Synchronized
    fun deleteWord(id: Long): Boolean =
        writableDatabase.delete("word", "id = ?", arrayOf(id.toString())) > 0

    /** 有効なセットの単語（セット順・単語順）。Web版 get_word_list_context と同じ順序。 */
    @Synchronized
    fun activeWords(): List<WordRow> = wordSets().filter { it.isActive }.flatMap { words(it.id) }

    /** 検証済みの単語セットを一括追加する。失敗時は全件ロールバックする。 */
    @Synchronized
    fun importWordSets(sets: List<ImportedWordSet>) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            sets.forEach { set ->
                val setId = database.insertOrThrow("word_set", null, ContentValues().apply {
                    put("name", set.name)
                    put("is_active", if (set.isActive) 1 else 0)
                })
                set.words.forEach { word ->
                    database.insertOrThrow("word", null, ContentValues().apply {
                        put("set_id", setId)
                        put("reading", word.reading)
                        put("replacement", word.replacement)
                    })
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    fun wipeAll() {
        writableDatabase.delete("word", null, null)
        writableDatabase.delete("word_set", null, null)
        writableDatabase.delete("history", null, null)
        writableDatabase.delete("batch_job", null, null)
    }
}
