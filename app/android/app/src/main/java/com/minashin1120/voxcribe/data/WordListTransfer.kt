package com.minashin1120.voxcribe.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

data class ImportedWord(val reading: String, val replacement: String)
data class ImportedWordSet(val name: String, val isActive: Boolean, val words: List<ImportedWord>)
data class WordListImportResult(val sets: Int, val words: Int)

/** Web版と共通のJSON形式で単語リストを入出力する。 */
object WordListTransfer {
    private const val MAX_BYTES = 1024 * 1024
    private const val MAX_SETS = 200
    private const val MAX_WORDS = 10_000

    fun export(db: Db, output: OutputStream) {
        val sets = JSONArray()
        db.wordSets().forEach { set ->
            val words = JSONArray()
            db.words(set.id).forEach { word ->
                words.put(JSONObject().put("reading", word.reading).put("replacement", word.replacement))
            }
            sets.put(
                JSONObject()
                    .put("name", set.name)
                    .put("is_active", set.isActive)
                    .put("words", words)
            )
        }
        val payload = JSONObject()
            .put("format", "voxcribe-word-lists")
            .put("version", 1)
            .put("word_sets", sets)
            .toString(2) + "\n"
        output.write(payload.toByteArray(Charsets.UTF_8))
    }

    fun importFrom(db: Db, input: InputStream): WordListImportResult {
        val raw = readLimited(input)
        val root = try {
            JSONObject(raw.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
        } catch (_: Exception) {
            throw IllegalArgumentException("JSONファイルの形式が正しくありません。")
        }
        if (root.opt("format") != "voxcribe-word-lists" || root.opt("version") != 1) {
            throw IllegalArgumentException("Voxcribeの単語リストファイルではありません。")
        }
        val sourceSets = root.opt("word_sets") as? JSONArray
            ?: throw IllegalArgumentException("単語セットの形式が正しくありません。")
        if (sourceSets.length() > MAX_SETS) {
            throw IllegalArgumentException("単語セットは${MAX_SETS}件以下にしてください。")
        }

        val parsed = ArrayList<ImportedWordSet>(sourceSets.length())
        var totalWords = 0
        for (setIndex in 0 until sourceSets.length()) {
            val sourceSet = sourceSets.opt(setIndex) as? JSONObject
                ?: throw IllegalArgumentException("単語セットの形式が正しくありません。")
            val rawName = sourceSet.opt("name") as? String
                ?: throw IllegalArgumentException("セット名は1〜100文字で指定してください。")
            val name = rawName.trim()
            if (name.isEmpty() || name.length > 100) {
                throw IllegalArgumentException("セット名は1〜100文字で指定してください。")
            }
            val activeValue = sourceSet.opt("is_active")
            if (activeValue !is Boolean) throw IllegalArgumentException("単語セットの形式が正しくありません。")
            val sourceWords = sourceSet.opt("words") as? JSONArray
                ?: throw IllegalArgumentException("単語セットの形式が正しくありません。")
            totalWords += sourceWords.length()
            if (totalWords > MAX_WORDS) throw IllegalArgumentException("単語は合計${MAX_WORDS}件以下にしてください。")

            val words = ArrayList<ImportedWord>(sourceWords.length())
            for (wordIndex in 0 until sourceWords.length()) {
                val sourceWord = sourceWords.opt(wordIndex) as? JSONObject
                    ?: throw IllegalArgumentException("単語の形式が正しくありません。")
                val readingValue = sourceWord.opt("reading") as? String
                    ?: throw IllegalArgumentException("読みと変換後はそれぞれ1〜255文字で指定してください。")
                val replacementValue = sourceWord.opt("replacement") as? String
                    ?: throw IllegalArgumentException("読みと変換後はそれぞれ1〜255文字で指定してください。")
                val reading = readingValue.trim()
                val replacement = replacementValue.trim()
                if (reading.isEmpty() || reading.length > 255 || replacement.isEmpty() || replacement.length > 255) {
                    throw IllegalArgumentException("読みと変換後はそれぞれ1〜255文字で指定してください。")
                }
                words += ImportedWord(reading, replacement)
            }
            parsed += ImportedWordSet(name, activeValue, words)
        }
        db.importWordSets(parsed)
        return WordListImportResult(parsed.size, totalWords)
    }

    private fun readLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_BYTES) throw IllegalArgumentException("ファイルサイズは1MB以下にしてください。")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
