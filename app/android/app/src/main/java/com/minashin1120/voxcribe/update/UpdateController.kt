package com.minashin1120.voxcribe.update

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.minashin1120.voxcribe.VoxcribeApp
import com.minashin1120.voxcribe.ai.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 起動時にGitHub Releaseを確認し、最新版でなければ更新を促す（Android版だけの仕組み。
 * サーバーとは通信しないスタンドアロン版のため、更新はGitHub Releaseから直接取得する）。
 */
class UpdateController(private val app: VoxcribeApp) {
    private val ctx: Context get() = app
    private val scope = app.appScope
    private val downloader = ApkDownloader(Http.client, Http.userAgent)

    var info by mutableStateOf<UpdateInfo?>(null)
        private set
    var dismissed by mutableStateOf(false)
        private set
    var downloading by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0f)
        private set
    var downloadedFile by mutableStateOf<File?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var checked = false

    fun checkOnStart() {
        if (checked) return
        checked = true
        val current = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (_: Exception) {
            null
        } ?: return
        if (!UpdateChecker.isReleaseVersion(current)) return
        scope.launch {
            val latest = withContext(Dispatchers.IO) {
                try { UpdateChecker.fetchLatest() } catch (_: Exception) { null }
            } ?: return@launch
            if (UpdateChecker.isNewer(current, latest.version)) info = latest
        }
    }

    fun dismiss() {
        dismissed = true
    }

    fun startDownload() {
        val target = info ?: return
        if (downloading) return
        downloading = true
        progress = 0f
        error = null
        scope.launch {
            try {
                downloadedFile = withContext(Dispatchers.IO) {
                    val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    downloader.download(target.downloadUrl, target.sizeBytes,
                        File(dir, "voxcribe-${target.version}.apk")) { progress = it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "ダウンロードに失敗しました。"
            } finally {
                downloading = false
            }
        }
    }
}
