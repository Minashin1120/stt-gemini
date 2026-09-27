package com.minashin1120.voxcribe.ai

import android.util.Base64
import android.util.Base64OutputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

sealed class GeminiPart {
    data class Text(val text: String) : GeminiPart()
    data class Audio(val file: File, val mime: String) : GeminiPart()
}

/**
 * app.py process_gemini_background の移植。
 * 音声は inline_data（base64）で送る。巨大ファイルでもメモリに載せないよう、base64 はストリーミング書き込みする。
 */
object GeminiClient {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
    private val JSON = "application/json".toMediaType()

    /** Dedicated Gemini Transcribe uses the Files and Interactions APIs. */
    fun transcribeDedicated(
        apiKey: String, file: File, mime: String, token: CancelToken,
        onStatus: (String) -> Unit, onProgress: ((Long, Long) -> Unit)? = null,
    ): String {
        if (token.isCancelled) throw CancelledException()
        onStatus(TaskPhase.SENDING)
        val start = Request.Builder().url("https://generativelanguage.googleapis.com/upload/v1beta/files")
            .header("x-goog-api-key", apiKey).header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", mime).header("Content-Type", "application/json")
            .post(JSONObject().put("file", JSONObject().put("display_name", file.name)).toString().toRequestBody(JSON)).build()
        val startCall = Http.client.newCall(start); token.attach(startCall)
        val uploadUrl = startCall.execute().use { r ->
            if (r.code !in 200..201) throw AiException("Gemini Files API Error ${r.code}")
            r.header("X-Goog-Upload-URL") ?: throw AiException("Gemini Files APIのアップロードURLを取得できませんでした")
        }
        val uploadBody = ProgressFileBody(file, mime.toMediaType()) { sent, total -> onProgress?.invoke(sent, total) }
        val upload = Request.Builder().url(uploadUrl).header("x-goog-api-key", apiKey)
            .header("X-Goog-Upload-Offset", "0").header("X-Goog-Upload-Command", "upload, finalize")
            .post(uploadBody).build()
        val uploadCall = Http.client.newCall(upload); token.attach(uploadCall)
        val fileUri = uploadCall.execute().use { r ->
            val raw = r.body.string()
            if (r.code !in 200..201) throw AiException("Gemini音声アップロードエラー ${r.code}")
            JSONObject(raw).optJSONObject("file")?.optString("uri")?.takeIf { it.isNotEmpty() }
                ?: throw AiException("Gemini Files APIの応答に音声URIがありません")
        }
        if (token.isCancelled) throw CancelledException()
        onStatus(TaskPhase.TRANSCRIBING)
        val body = JSONObject().put("model", "gemini-3.5-transcribe")
            .put("input", org.json.JSONArray().put(JSONObject().put("type", "audio").put("uri", fileUri).put("mime_type", mime)))
        val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/interactions")
            .header("x-goog-api-key", apiKey).post(body.toString().toRequestBody(JSON)).build()
        val call = Http.client.newCall(req); token.attach(call)
        return call.execute().use { r ->
            val raw = r.body.string()
            if (r.code != 200) throw AiException("Gemini Transcribe API Error ${r.code}")
            val obj = JSONObject(raw)
            obj.optString("output_text").ifEmpty {
                val outputs = obj.optJSONArray("outputs") ?: return@use ""
                buildString {
                    for (i in 0 until outputs.length()) {
                        val content = outputs.optJSONObject(i)?.optJSONArray("content") ?: continue
                        for (j in 0 until content.length()) {
                            val part = content.optJSONObject(j) ?: continue
                            if (part.optString("type") == "text") append(part.optString("text"))
                        }
                    }
                }
            }
        }
    }

    /** Dedicated Gemini Live Transcribe session fed with a saved recording as PCM16 audio. */
    fun transcribeLive(
        apiKey: String, file: File, token: CancelToken, onStatus: (String) -> Unit,
        onText: (String) -> Unit,
    ): String {
        if (token.isCancelled) throw CancelledException()
        val pcm = try { AudioDecoder.decodeToPcm16Mono(file, 16000) }
            catch (_: Exception) { throw AiException("音声変換に失敗しました。") }
        val done = CountDownLatch(1); val ready = CountDownLatch(1)
        val text = StringBuilder(); var error: String? = null; lateinit var ws: WebSocket
        onStatus(TaskPhase.SENDING)
        val request = Request.Builder()
            .url("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey")
            .build()
        ws = Http.client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("setup", JSONObject().put("model", "models/gemini-3.5-transcribe-live")
                    .put("generationConfig", JSONObject().put("responseModalities", org.json.JSONArray().put("TEXT"))
                        .put("inputAudioTranscription", JSONObject()))).toString())
            }
            override fun onMessage(webSocket: WebSocket, message: String) {
                try {
                    val event = JSONObject(message)
                    if (event.has("setupComplete")) {
                        ready.countDown(); onStatus(TaskPhase.RECEIVING)
                        for (off in pcm.indices step 32000) {
                            if (token.isCancelled) break
                            val end = minOf(pcm.size, off + 32000)
                            val b64 = Base64.encodeToString(pcm, off, end - off, Base64.NO_WRAP)
                            webSocket.send(JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
                                .put("data", b64).put("mimeType", "audio/pcm;rate=16000"))).toString())
                        }
                        webSocket.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())
                    }
                    val server = event.optJSONObject("serverContent")
                    val delta = server?.optJSONObject("inputTranscription")?.optString("text", "").orEmpty()
                    if (delta.isNotEmpty()) { text.append(delta); onStatus(TaskPhase.TRANSCRIBING); onText(delta) }
                    if (server?.optBoolean("turnComplete", false) == true) { done.countDown(); webSocket.close(1000, null) }
                    event.optJSONObject("error")?.let { error = it.optString("message"); ready.countDown(); done.countDown() }
                } catch (_: Exception) { }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                error = t.message ?: "Gemini Live API接続エラー"; ready.countDown(); done.countDown()
            }
        })
        token.attach(ws)
        if (!ready.await(30, TimeUnit.SECONDS)) { ws.cancel(); throw AiException("Gemini Live接続がタイムアウトしました") }
        if (token.isCancelled) throw CancelledException()
        done.await(300, TimeUnit.SECONDS); ws.close(1000, null)
        if (token.isCancelled) throw CancelledException()
        if (error != null) throw AiException("Gemini Live APIエラー")
        return text.toString()
    }

    private class PayloadBody(
        private val parts: List<GeminiPart>,
        private val thinkingLevel: String?,
        private val onProgress: ((Long, Long) -> Unit)?,
    ) : RequestBody() {
        override fun contentType() = JSON
        override fun writeTo(sink: BufferedSink) {
            val total = parts.sumOf { if (it is GeminiPart.Audio) it.file.length() else 0L }
            var sent = 0L
            sink.writeUtf8("{\"contents\":[{\"parts\":[")
            parts.forEachIndexed { i, p ->
                if (i > 0) sink.writeUtf8(",")
                when (p) {
                    is GeminiPart.Text -> sink.writeUtf8("{\"text\":").writeUtf8(JSONObject.quote(p.text)).writeUtf8("}")
                    is GeminiPart.Audio -> {
                        sink.writeUtf8("{\"inline_data\":{\"mime_type\":").writeUtf8(JSONObject.quote(p.mime))
                        sink.writeUtf8(",\"data\":\"")
                        sink.flush()
                        val b64 = Base64OutputStream(object : java.io.OutputStream() {
                            override fun write(b: Int) { sink.writeByte(b) }
                            override fun write(b: ByteArray, off: Int, len: Int) { sink.write(b, off, len) }
                        }, Base64.NO_WRAP or Base64.NO_CLOSE)
                        p.file.inputStream().use { input ->
                            val buf = ByteArray(48 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                b64.write(buf, 0, n)
                                sent += n
                                onProgress?.invoke(sent, total)
                            }
                        }
                        b64.close()
                        sink.writeUtf8("\"}}")
                    }
                }
            }
            sink.writeUtf8("]}]")
            if (thinkingLevel != null) {
                sink.writeUtf8(",\"generationConfig\":{\"thinkingConfig\":{\"includeThoughts\":true,\"thinkingLevel\":")
                sink.writeUtf8(JSONObject.quote(thinkingLevel)).writeUtf8("}}")
            }
            sink.writeUtf8("}")
        }
    }

    /**
     * ストリーミング生成。thought/text の差分をコールバックで返す。
     * 戻り値は (thought全文, text全文)。
     */
    fun stream(
        apiKey: String,
        model: String,
        parts: List<GeminiPart>,
        thinkingLevel: String,
        token: CancelToken,
        onStatus: (String) -> Unit,
        onThought: (String) -> Unit,
        onText: (String) -> Unit,
        onUploadProgress: ((Long, Long) -> Unit)? = null,
    ): Pair<String, String> {
        val url = "$BASE$model:streamGenerateContent?alt=sse"
        val maxRetries = 3
        var retryDelay = 2000L
        val thought = StringBuilder()
        val text = StringBuilder()

        for (attempt in 0..maxRetries) {
            if (token.isCancelled) throw CancelledException()
            onStatus(TaskPhase.SENDING)
            val req = Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey)
                .header("User-Agent", Http.userAgent)
                .post(PayloadBody(parts, thinkingLevel, onUploadProgress))
                .build()
            val call = Http.client.newCall(req)
            token.attach(call)
            val resp = try {
                call.execute()
            } catch (e: IOException) {
                if (token.isCancelled) throw CancelledException()
                throw AiException("APIリクエスト中にエラーが発生しました")
            }
            resp.use { r ->
                if (r.code == 429) {
                    if (attempt < maxRetries) {
                        val until = System.currentTimeMillis() + retryDelay
                        while (System.currentTimeMillis() < until) {
                            if (token.isCancelled) throw CancelledException()
                            Thread.sleep(100)
                        }
                        retryDelay *= 2
                        return@use
                    }
                    throw AiException("API Error 429: リクエスト制限に達しました。時間をおいて再度お試しください。")
                }
                if (r.code != 200) throw AiException("API Error ${r.code}")
                onStatus(TaskPhase.RECEIVING)
                try {
                    val source = r.body.source()
                    var switched = false
                    while (true) {
                        if (token.isCancelled) throw CancelledException()
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data: ")) continue
                        val obj = try { JSONObject(line.substring(6)) } catch (_: Exception) { continue }
                        val partsArr = obj.optJSONArray("candidates")?.optJSONObject(0)
                            ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                        for (i in 0 until partsArr.length()) {
                            val part = partsArr.optJSONObject(i) ?: continue
                            if (part.optBoolean("thought", false)) {
                                val t = part.optString("text", "")
                                if (t.isNotEmpty()) {
                                    thought.append(t)
                                    onThought(t)
                                }
                            } else if (part.has("text")) {
                                val t = part.optString("text", "")
                                if (!switched) {
                                    switched = true
                                    onStatus(TaskPhase.TRANSCRIBING)
                                }
                                text.append(t)
                                onText(t)
                            }
                        }
                    }
                } catch (e: CancelledException) {
                    throw e
                } catch (e: IOException) {
                    if (token.isCancelled) throw CancelledException()
                    throw AiException("APIリクエスト中にエラーが発生しました")
                }
                return thought.toString() to text.toString()
            }
        }
        throw AiException("API Error 429: リクエスト制限に達しました。時間をおいて再度お試しください。")
    }

    /** app.py /api/yomigana/generate（非ストリーミング） */
    fun yomigana(apiKey: String, model: String, word: String): String {
        val body = JSONObject().put(
            "contents",
            org.json.JSONArray().put(JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", Prompts.yomigana(word)))))
        )
        val req = Request.Builder()
            .url("$BASE$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .header("User-Agent", Http.userAgent)
            .post(body.toString().toRequestBody(JSON))
            .build()
        val client = Http.client.newBuilder().callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
        try {
            client.newCall(req).execute().use { r ->
                val raw = r.body.string()
                if (r.code != 200) {
                    val msg = try { JSONObject(raw).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                    throw AiException("API Error: ${r.code}" + if (!msg.isNullOrEmpty()) " ($msg)" else "")
                }
                return JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                    .getString("text").trim()
            }
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            throw AiException("生成に失敗しました")
        }
    }
}

class AiException(message: String) : Exception(message)

/** app.py PHASE_LABELS */
object TaskPhase {
    const val SENDING = "APIサーバーに送信中..."
    const val RECEIVING = "解析中..."
    const val TRANSCRIBING = "書き起こし中..."
}
