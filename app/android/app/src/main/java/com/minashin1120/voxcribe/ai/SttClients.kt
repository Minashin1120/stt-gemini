package com.minashin1120.voxcribe.ai

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** app.py process_grok_stt_background の移植 */
object GrokClient {
    private val MIME = mapOf(
        ".mp3" to "audio/mpeg", ".wav" to "audio/wav", ".m4a" to "audio/mp4", ".mp4" to "audio/mp4",
        ".webm" to "audio/webm", ".ogg" to "audio/ogg", ".opus" to "audio/opus", ".flac" to "audio/flac",
        ".aac" to "audio/aac", ".mkv" to "audio/x-matroska",
    )

    fun transcribe(
        apiKey: String,
        file: File,
        token: CancelToken,
        onStatus: (String) -> Unit,
        onUploadProgress: ((Long, Long) -> Unit)? = null,
    ): String {
        if (token.isCancelled) throw CancelledException()
        val ext = file.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }.lowercase()
        val mime = MIME[ext] ?: "audio/mpeg"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "grok-voice-transcribe-2.0")
            .addFormDataPart("file", file.name, ProgressFileBody(file, mime.toMediaType()) { s, t -> onUploadProgress?.invoke(s, t) })
            .build()
        onStatus(TaskPhase.SENDING)
        val call = Http.client.newCall(
            Request.Builder().url("https://api.x.ai/v1/stt")
                .header("Authorization", "Bearer $apiKey")
                .header("User-Agent", Http.userAgent)
                .post(body).build()
        )
        token.attach(call)
        val resp: Response = try {
            call.execute()
        } catch (e: SocketTimeoutException) {
            if (token.isCancelled) throw CancelledException()
            throw AiException("xAI STT APIへのリクエストがタイムアウトしました。")
        } catch (e: InterruptedIOException) {
            if (token.isCancelled) throw CancelledException()
            throw AiException("xAI STT APIへのリクエストがタイムアウトしました。")
        } catch (e: IOException) {
            if (token.isCancelled) throw CancelledException()
            throw AiException("xAI STT APIへの接続に失敗しました。")
        }
        resp.use { r ->
            when (r.code) {
                200 -> Unit
                401 -> throw AiException("xAI APIキーが無効です。設定画面で確認してください。")
                413 -> throw AiException("音声ファイルが最大サイズ(500MB)を超えています。")
                429 -> throw AiException("xAI APIのレート制限に達しました。時間をおいて再度お試しください。")
                else -> throw AiException("xAI STT API Error ${r.code}")
            }
            onStatus(TaskPhase.RECEIVING)
            val text = try {
                JSONObject(r.body.string()).optString("text", "")
            } catch (e: Exception) {
                throw AiException("xAI STT処理中にエラーが発生しました")
            }
            onStatus(TaskPhase.TRANSCRIBING)
            return text
        }
    }
}

/**
 * Grok Live (grok-live-transcribe) のセッション。xAIの `wss://api.x.ai/v1/stt` に
 * インターリーブPCM16をpushで送り続け、部分/確定の文字起こしをコールバックで受け取る。
 * Android版はサーバーを経由せずxAIへ直接接続する(スタンドアロン設計通り)。
 */
class GrokLiveSession internal constructor(
    private val ws: WebSocket,
    private val token: CancelToken,
    private val closedLatch: CountDownLatch,
) {
    /** インターリーブPCM16(LE)のバイナリフレームを1つ送る */
    fun sendAudio(pcm16: ByteArray) {
        if (token.isCancelled) return
        ws.send(ByteString.of(*pcm16))
    }

    /** {"type":"audio.done"} を送り、サーバーがcloseするまで最大timeoutMs待つ(bounded)。
     * 短すぎると、末尾の未確定発話がspeech_final前に停止された場合にxAIからの
     * 最終transcript.doneが間に合わず、確定テキストが欠落する原因になるため、
     * ある程度余裕を持たせている(Web版の中継タイムアウトと合わせて8秒)。 */
    fun finish(timeoutMs: Long = 8000) {
        try {
            ws.send(JSONObject().put("type", "audio.done").toString())
        } catch (_: Exception) {
        }
        closedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        ws.cancel()
    }

    fun cancel() {
        token.cancel()
        ws.cancel()
    }
}

fun GrokClient.startLiveSession(
    apiKey: String,
    sampleRate: Int,
    channels: Int,
    token: CancelToken,
    onStatus: (String) -> Unit,
    onPartial: (channelIndex: Int, text: String, isFinal: Boolean, speechFinal: Boolean) -> Unit,
    onFinalText: (channelIndex: Int, text: String) -> Unit,
    onError: (String) -> Unit,
): GrokLiveSession {
    val multichannel = channels >= 2
    val url = "wss://api.x.ai/v1/stt?sample_rate=$sampleRate&encoding=pcm&interim_results=true" +
        "&endpointing=400&multichannel=$multichannel&channels=$channels&model=grok-voice-transcribe-2.0"
    val closedLatch = CountDownLatch(1)
    onStatus(TaskPhase.SENDING)
    val req = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $apiKey")
        .header("User-Agent", Http.userAgent)
        .build()
    val ws = Http.client.newBuilder().pingInterval(20, TimeUnit.SECONDS).build()
        .newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onStatus(TaskPhase.RECEIVING)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val ev = try { JSONObject(text) } catch (_: Exception) { return }
                val chIdx = ev.optInt("channel_index", 0)
                when (ev.optString("type")) {
                    "transcript.partial" -> onPartial(
                        chIdx, ev.optString("text", ""),
                        ev.optBoolean("is_final", false), ev.optBoolean("speech_final", false)
                    )
                    "transcript.done" -> onFinalText(chIdx, ev.optString("text", ""))
                    "error" -> onError(ev.optJSONObject("error")?.optString("message") ?: ev.optString("message", "Unknown error"))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closedLatch.countDown() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onError(t.message ?: "接続エラー")
                closedLatch.countDown()
            }
        })
    token.attach(ws)
    return GrokLiveSession(ws, token, closedLatch)
}

/** app.py OpenAI gpt-transcribe / gpt-live-transcribe の移植 */
object OpenAiClient {

    private fun mapIoError(e: IOException, token: CancelToken): Exception {
        if (token.isCancelled) return CancelledException()
        return if (e is SocketTimeoutException || e is InterruptedIOException)
            AiException("OpenAI APIへのリクエストがタイムアウトしました。")
        else AiException("OpenAI APIへの接続に失敗しました。")
    }

    fun transcribe(
        apiKey: String,
        file: File,
        token: CancelToken,
        onStatus: (String) -> Unit,
        onText: (String) -> Unit,
        onUploadProgress: ((Long, Long) -> Unit)? = null,
    ): String {
        if (token.isCancelled) throw CancelledException()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", file.name,
                ProgressFileBody(file, "application/octet-stream".toMediaType()) { s, t -> onUploadProgress?.invoke(s, t) }
            )
            .addFormDataPart("model", "gpt-transcribe")
            .addFormDataPart("stream", "true")
            .build()
        onStatus(TaskPhase.SENDING)
        val call = Http.client.newCall(
            Request.Builder().url("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer $apiKey")
                .header("User-Agent", Http.userAgent)
                .post(body).build()
        )
        token.attach(call)
        val resp = try { call.execute() } catch (e: IOException) { throw mapIoError(e, token) }
        resp.use { r ->
            when (r.code) {
                200 -> Unit
                401 -> throw AiException("OpenAI APIキーが無効です。設定画面で確認してください。")
                413 -> throw AiException("音声ファイルが最大サイズ(25MB)を超えています。")
                429 -> throw AiException("OpenAI APIのレート制限に達しました。時間をおいて再度お試しください。")
                else -> throw AiException("OpenAI Transcription API Error ${r.code}")
            }
            onStatus(TaskPhase.RECEIVING)
            val full = StringBuilder()
            var switched = false
            try {
                val src = r.body.source()
                while (true) {
                    if (token.isCancelled) throw CancelledException()
                    val line = src.readUtf8Line() ?: break
                    if (!line.startsWith("data: ")) continue
                    val data = line.substring(6).trim()
                    if (data == "[DONE]") break
                    val ev = try { JSONObject(data) } catch (_: Exception) { continue }
                    when (ev.optString("type")) {
                        "transcript.text.delta" -> {
                            val d = ev.optString("delta", "")
                            if (!switched) {
                                switched = true
                                onStatus(TaskPhase.TRANSCRIBING)
                            }
                            full.append(d)
                            onText(d)
                        }
                        "transcript.text.done" -> {
                            val t = ev.optString("text", full.toString())
                            full.setLength(0)
                            full.append(t)
                        }
                    }
                }
            } catch (e: CancelledException) {
                throw e
            } catch (e: IOException) {
                throw mapIoError(e, token)
            }
            return full.toString()
        }
    }

    /**
     * gpt-live-transcribe: 24kHz モノラル PCM16 に変換して Realtime WebSocket へ送る。
     * サーバー版と違い、completed を受信した時点で終了する。
     */
    fun liveTranscribe(
        apiKey: String,
        file: File,
        token: CancelToken,
        onStatus: (String) -> Unit,
        onText: (String) -> Unit,
    ): String {
        if (token.isCancelled) throw CancelledException()
        val pcm = try {
            AudioDecoder.decodeToPcm16Mono(file, 24000)
        } catch (e: Exception) {
            throw AiException("音声変換に失敗しました。")
        }
        if (token.isCancelled) throw CancelledException()

        val full = StringBuilder()
        val done = CountDownLatch(1)
        var error: String? = null
        var completed = false

        onStatus(TaskPhase.SENDING)
        val req = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=gpt-live-transcribe")
            .header("Authorization", "Bearer $apiKey")
            .header("User-Agent", Http.userAgent)
            .build()
        val ws = Http.client.newBuilder().pingInterval(20, TimeUnit.SECONDS).build()
            .newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val update = JSONObject().put("type", "session.update").put(
                        "session", JSONObject().put("type", "transcription").put(
                            "audio", JSONObject().put(
                                "input", JSONObject()
                                    .put("format", JSONObject().put("type", "audio/pcm").put("rate", 24000))
                                    .put("transcription", JSONObject().put("model", "gpt-live-transcribe"))
                                    .put("turn_detection", JSONObject.NULL)
                            )
                        )
                    )
                    webSocket.send(update.toString())
                    onStatus(TaskPhase.RECEIVING)
                    Thread {
                        try {
                            val chunk = 24000 * 2
                            var off = 0
                            while (off < pcm.size) {
                                if (token.isCancelled) break
                                val end = minOf(pcm.size, off + chunk)
                                val b64 = Base64.encodeToString(pcm, off, end - off, Base64.NO_WRAP)
                                webSocket.send(JSONObject().put("type", "input_audio_buffer.append").put("audio", b64).toString())
                                off = end
                                Thread.sleep(50)
                            }
                            if (!token.isCancelled) webSocket.send(JSONObject().put("type", "input_audio_buffer.commit").toString())
                        } catch (_: Exception) {
                        }
                    }.start()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val ev = try { JSONObject(text) } catch (_: Exception) { return }
                    when (ev.optString("type")) {
                        "conversation.item.input_audio_transcription.delta" -> {
                            val d = ev.optString("delta", "")
                            if (d.isNotEmpty()) {
                                full.append(d)
                                onStatus(TaskPhase.TRANSCRIBING)
                                onText(d)
                            }
                        }
                        "conversation.item.input_audio_transcription.completed" -> {
                            val t = ev.optString("transcript", "")
                            if (t.isNotEmpty()) {
                                full.setLength(0)
                                full.append(t)
                            }
                            completed = true
                            webSocket.close(1000, null)
                            done.countDown()
                        }
                        "error" -> {
                            error = ev.optJSONObject("error")?.optString("message")?.ifEmpty { null } ?: "Unknown WebSocket error"
                            webSocket.close(1000, null)
                            done.countDown()
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.countDown() }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); done.countDown() }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!completed && error == null) error = t.message ?: "Unknown WebSocket error"
                    done.countDown()
                }
            })
        token.attach(ws)
        val deadline = System.currentTimeMillis() + 300_000
        while (!done.await(100, TimeUnit.MILLISECONDS)) {
            if (token.isCancelled) {
                ws.cancel()
                throw CancelledException()
            }
            if (System.currentTimeMillis() > deadline) {
                ws.close(1000, null)
                break
            }
        }
        if (token.isCancelled) throw CancelledException()
        error?.let { throw AiException("OpenAI Realtimeエラー: $it") }
        return full.toString()
    }
}
