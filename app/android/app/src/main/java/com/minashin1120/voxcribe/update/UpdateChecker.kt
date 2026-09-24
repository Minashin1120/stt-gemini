package com.minashin1120.voxcribe.update

import com.minashin1120.voxcribe.ai.Http
import okhttp3.Request
import org.json.JSONObject

/** GitHub Releases の最新版情報（Android版だけの仕組み。Web版には対応なし） */
data class UpdateInfo(
    val tag: String,
    val version: String,
    val notes: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

object UpdateChecker {
    private const val RELEASES_LATEST_URL = "https://api.github.com/repos/Minashin1120/stt-gemini/releases/latest"
    private const val APK_ASSET_NAME = "app-release.apk"

    /** 最新の GitHub Release と、そこに添付された app-release.apk を取得する */
    fun fetchLatest(): UpdateInfo? {
        val req = Request.Builder()
            .url(RELEASES_LATEST_URL)
            .header("User-Agent", Http.userAgent)
            .header("Accept", "application/vnd.github+json")
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val json = JSONObject(resp.body?.string() ?: return null)
            val tag = json.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
            val version = tag.removePrefix("v")
            val notes = json.optString("body").trim()
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name") == APK_ASSET_NAME) {
                    val url = asset.optString("browser_download_url").takeIf { it.isNotBlank() } ?: return null
                    return UpdateInfo(tag, version, notes, url, asset.optLong("size"))
                }
            }
            return null
        }
    }

    /** "X.Y.Z" のみ対象。debugビルドの versionName（"0.0.<run>-debug"）は更新確認の対象外 */
    fun isReleaseVersion(v: String): Boolean = Regex("""^\d+\.\d+\.\d+$""").matches(v)

    fun isNewer(current: String, latest: String): Boolean {
        val c = current.split(".").mapNotNull { it.toIntOrNull() }
        val l = latest.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(c.size, l.size)) {
            val cv = c.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }
}
