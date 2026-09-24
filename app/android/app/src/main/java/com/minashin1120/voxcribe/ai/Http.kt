package com.minashin1120.voxcribe.ai

import android.os.Build
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.WebSocket
import okio.BufferedSink
import okio.buffer
import okio.source
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object Http {
    /** app.py の timeout=(10, 600) に合わせる */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(600, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val userAgent = "Voxcribe-Android/1.0 (Android ${Build.VERSION.RELEASE})"
}

/** 実行中タスクの停止用トークン（Web版 /api/tasks/<id>/cancel 相当） */
class CancelToken {
    private val flag = AtomicBoolean(false)
    @Volatile private var call: Call? = null
    @Volatile private var socket: WebSocket? = null

    val isCancelled: Boolean get() = flag.get()

    fun attach(c: Call) {
        call = c
        if (flag.get()) c.cancel()
    }

    fun attach(ws: WebSocket) {
        socket = ws
        if (flag.get()) ws.cancel()
    }

    fun cancel() {
        flag.set(true)
        call?.cancel()
        socket?.cancel()
    }
}

class CancelledException : Exception("処理を停止しました")

/** アップロード進捗付きのファイル本体 */
class ProgressFileBody(
    private val file: File,
    private val type: MediaType?,
    private val onProgress: (Long, Long) -> Unit,
) : RequestBody() {
    override fun contentType() = type
    override fun contentLength() = file.length()
    override fun writeTo(sink: BufferedSink) {
        val total = file.length()
        var sent = 0L
        file.source().buffer().use { src ->
            val buf = okio.Buffer()
            while (true) {
                val n = src.read(buf, 64 * 1024)
                if (n <= 0) break
                sink.write(buf, n)
                sent += n
                onProgress(sent, total)
            }
        }
    }
}
