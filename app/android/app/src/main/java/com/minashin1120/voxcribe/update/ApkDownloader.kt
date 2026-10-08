package com.minashin1120.voxcribe.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** APK専用。大きいファイルは4接続で取得し、AI用HTTP設定には影響しない。 */
internal class ApkDownloader(client: OkHttpClient, private val userAgent: String) {
    private val client = client.newBuilder()
        // HTTP/2の単一接続に集約せず、接続ごとの帯域を利用する。
        .protocols(listOf(Protocol.HTTP_1_1))
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun download(url: String, size: Long, output: File, onProgress: (Float) -> Unit): File {
        val partial = File(output.parentFile, "${output.name}.part")
        val progress = Progress(size, onProgress)
        try {
            val remote = try {
                execute(request(url).head().build()) { response ->
                    val length = response.header("Content-Length")?.toLongOrNull() ?: -1L
                    val etag = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                    if (response.isSuccessful && response.header("Accept-Ranges") == "bytes" &&
                        length >= MIN_PARALLEL_SIZE && (size <= 0 || size == length) && etag != null
                    ) Remote(response.request.url.toString(), length, etag) else null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                null
            }

            var parallelComplete = false
            if (remote != null) {
                progress.total = remote.size
                try {
                    downloadRanges(remote, partial, progress)
                    parallelComplete = true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    // coroutineScopeが全接続の停止を待ってから通常取得へ戻す。
                    progress.reset()
                }
            }
            if (!parallelComplete) downloadSingle(url, size, partial, progress)
            if (!partial.renameTo(output)) throw IOException("APKの保存に失敗しました")
            onProgress(1f)
            return output
        } finally {
            partial.delete()
        }
    }

    private suspend fun downloadRanges(remote: Remote, output: File, progress: Progress) {
        RandomAccessFile(output, "rw").use { it.setLength(remote.size) }
        coroutineScope {
            (0 until CONNECTIONS).map { index ->
                async {
                    val start = remote.size * index / CONNECTIONS
                    val end = remote.size * (index + 1) / CONNECTIONS - 1
                    val request = request(remote.url)
                        .header("Range", "bytes=$start-$end")
                        .header("If-Range", remote.etag)
                        .build()
                    execute(request) { response ->
                        if (response.code != 206 ||
                            response.header("Content-Range") != "bytes $start-$end/${remote.size}" ||
                            response.header("ETag")?.let { it != remote.etag } == true
                        ) throw IOException("APKの範囲応答が一致しません")
                        RandomAccessFile(output, "rw").use { sink ->
                            sink.seek(start)
                            copy(response, end - start + 1, progress) { buffer, count ->
                                sink.write(buffer, 0, count)
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun downloadSingle(url: String, size: Long, output: File, progress: Progress) {
        execute(request(url).build()) { response ->
            if (response.code != 200) throw IOException("HTTP ${response.code}")
            val length = response.body?.contentLength() ?: -1L
            if (size > 0 && length >= 0 && length != size) throw IOException("APKのサイズが一致しません")
            val expected = if (size > 0) size else length
            progress.total = expected
            output.outputStream().buffered(BUFFER_SIZE).use { sink ->
                copy(response, expected, progress) { buffer, count -> sink.write(buffer, 0, count) }
            }
        }
    }

    private fun copy(response: Response, expected: Long, progress: Progress, write: (ByteArray, Int) -> Unit) {
        val body = response.body ?: throw IOException("APKが空です")
        if (response.header("Content-Encoding")?.let { it != "identity" } == true) {
            throw IOException("APKのエンコードが一致しません")
        }
        var written = 0L
        body.byteStream().use { source ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                written += count
                if (expected >= 0 && written > expected) throw IOException("APKのサイズ超過")
                write(buffer, count)
                progress.add(count)
            }
        }
        if (written == 0L || (expected >= 0 && written != expected)) throw IOException("APKの取得が不完全です")
    }

    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", userAgent)
        .header("Accept-Encoding", "identity")

    /** 親のキャンセル時に、ブロッキング読み取り中の接続も直ちに閉じる。 */
    private suspend fun <T> execute(request: Request, consume: (Response) -> T): T = coroutineScope {
        val call = client.newCall(request)
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) { call.execute().use(consume) }
        } finally {
            cancellation.cancel()
        }
    }

    private data class Remote(val url: String, val size: Long, val etag: String)

    private class Progress(var total: Long, private val notify: (Float) -> Unit) {
        private var received = 0L

        // 全接続の受信量を集計し、受信ごとに更新する（時間による間引きなし）。
        @Synchronized fun add(count: Int) {
            received += count
            if (total > 0) notify((received.toFloat() / total).coerceIn(0f, 1f))
        }

        @Synchronized fun reset() {
            received = 0L
            notify(0f)
        }
    }

    private companion object {
        const val CONNECTIONS = 4
        const val MIN_PARALLEL_SIZE = 4L * 1024 * 1024
        const val BUFFER_SIZE = 64 * 1024
    }
}
