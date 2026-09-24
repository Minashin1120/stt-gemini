package com.minashin1120.voxcribe.data

import android.content.Context
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedAudio(val file: File, val displayName: String, val size: Long)

/**
 * Web版 uploads/ ディレクトリ相当。音声は端末内の filesDir/audio に保存する。
 */
class AudioStore(context: Context) {
    val dir: File = File(context.filesDir, "audio").apply { mkdirs() }
    private val rng = SecureRandom()

    fun newFile(ext: String): File {
        val hex = ByteArray(4).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        return File(dir, "local_${System.nanoTime()}_$hex$ext")
    }

    fun resolve(name: String?): File? {
        if (name.isNullOrBlank() || name.contains('/') || name.contains("..")) return null
        val f = File(dir, name)
        return if (f.exists()) f else null
    }

    /** Web版 /api/files と同じく新しい順 */
    fun list(): List<SavedAudio> {
        val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US)
        return (dir.listFiles()?.filter { it.isFile } ?: emptyList())
            .sortedByDescending { it.lastModified() }
            .map { SavedAudio(it, fmt.format(Date(it.lastModified())), it.length()) }
    }

    fun deleteAllExcept(keep: File?) {
        dir.listFiles()?.forEach { if (keep == null || it.absolutePath != keep.absolutePath) it.delete() }
    }

    fun deleteOlderThan(cutoffMs: Long) {
        dir.listFiles()?.forEach { if (it.lastModified() < cutoffMs) it.delete() }
    }

    companion object {
        /** Web版 ALLOWED_AUDIO_EXTENSIONS と同じ対応表 */
        val MIME_BY_EXT = linkedMapOf(
            ".mp3" to "audio/mpeg",
            ".wav" to "audio/wav",
            ".m4a" to "audio/mp4",
            ".mp4" to "audio/mp4",
            ".webm" to "audio/webm",
            ".ogg" to "audio/ogg",
        )

        fun extOf(name: String): String {
            val i = name.lastIndexOf('.')
            return if (i >= 0) name.substring(i).lowercase(Locale.ROOT) else ""
        }
    }
}
