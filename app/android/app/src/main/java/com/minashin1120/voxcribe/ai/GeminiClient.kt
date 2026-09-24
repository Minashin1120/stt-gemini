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
