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

data class WordSetRow(val id: Long, val name: String, val isActive: Boolean)

data class WordRow(val id: Long, val setId: Long, val reading: String, val replacement: String)

/**
 * Web版の MariaDB（History / WordSet / Word テーブル）に相当する端末内DB。
 */
class Db(context: Context) : SQLiteOpenHelper(context, "voxcribe.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
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

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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

    @Synchronized
    fun wipeAll() {
        writableDatabase.delete("word", null, null)
        writableDatabase.delete("word_set", null, null)
        writableDatabase.delete("history", null, null)
    }
}
