package com.minashin1120.voxcribe.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Web版 localStorage と同じキー名で端末設定を保存する。
 * stt_m / stt_t_<model> / stt_n / stt_r / stt_fl / stt_f / stt_yomigana_m / app_theme
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("voxcribe_prefs", Context.MODE_PRIVATE)

    fun getString(key: String): String? = sp.getString(key, null)
    fun putString(key: String, value: String) = sp.edit { putString(key, value) }

    var model: String?
        get() = getString("stt_m")
        set(v) { if (v != null) putString("stt_m", v) }

    fun thinkingFor(model: String): String? = getString("stt_t_$model")
    fun setThinkingFor(model: String, level: String) = putString("stt_t_$model", level)

    var noise: String?
        get() = getString("stt_n")
        set(v) { if (v != null) putString("stt_n", v) }

    var rephrase: String?
        get() = getString("stt_r")
        set(v) { if (v != null) putString("stt_r", v) }

    var filler: String?
        get() = getString("stt_fl")
        set(v) { if (v != null) putString("stt_fl", v) }

    var format: String?
        get() = getString("stt_f")
        set(v) { if (v != null) putString("stt_f", v) }

    var yomiganaModel: String
        get() = getString("stt_yomigana_m") ?: "gemini-3.5-flash"
        set(v) = putString("stt_yomigana_m", v)

    var theme: String
        get() = getString("app_theme") ?: ""
        set(v) = putString("app_theme", v)

    /** Web版 User.retention_minutes 相当（既定10分） */
    var retentionMinutes: Int
        get() = sp.getInt("retention_minutes", 10)
        set(v) = sp.edit { putInt("retention_minutes", v.coerceIn(1, 1440)) }

    var welcomeSeen: Boolean
        get() = sp.getBoolean("welcome_seen", false)
        set(v) = sp.edit { putBoolean("welcome_seen", v) }

    /** Web版 session['last_audio_file'] / ['last_audio_mime'] 相当 */
    var lastAudioFile: String?
        get() = getString("last_audio_file")
        set(v) = sp.edit { if (v == null) remove("last_audio_file") else putString("last_audio_file", v) }

    var lastAudioMime: String?
        get() = getString("last_audio_mime")
        set(v) = sp.edit { if (v == null) remove("last_audio_mime") else putString("last_audio_mime", v) }

    fun clearAll() = sp.edit { clear() }
}
