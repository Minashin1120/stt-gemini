package com.minashin1120.voxcribe.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Gemini Batch API クライアント（Web版 app/batch.py の移植）。
 * 公式 Batch があるのは Gemini 通常モデル（generateContent 系）のみ。
 * 流れ: Files API へ音声と JSONL をアップロード → models/{m}:batchGenerateContent → batches/{id} をポーリング
 *      → dest.responsesFile をダウンロードして結果 JSONL をパースする。
 */
object BatchClient {
    private const val ROOT = "https://generativelanguage.googleapis.com"
    private val JSON = "application/json".toMediaType()

    data class GeminiFile(val name: String, val uri: String, val state: String)
    data class JobState(val status: String, val responsesFile: String?, val error: String)
    data class Results(val thought: String, val text: String, val error: String)

    private fun get(apiKey: String, url: String, timeoutSec: Long = 60): okhttp3.Response {
        val req = Request.Builder().url(url).header("x-goog-api-key", apiKey).header("User-Agent", Http.userAgent).get().build()
        val client = Http.client.newBuilder().callTimeout(timeoutSec, java.util.concurrent.TimeUnit.SECONDS).build()
        return client.newCall(req).execute()
    }

    /** Files API (resumable) へアップロードし、ファイル情報を返す */
    fun uploadFile(
        apiKey: String, body: RequestBody, length: Long, mime: String, displayName: String, token: CancelToken,
    ): GeminiFile {
        if (token.isCancelled) throw CancelledException()
        val start = Request.Builder().url("$ROOT/upload/v1beta/files")
            .header("x-goog-api-key", apiKey).header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", length.toString())
            .header("X-Goog-Upload-Header-Content-Type", mime).header("Content-Type", "application/json")
            .post(JSONObject().put("file", JSONObject().put("display_name", displayName)).toString().toRequestBody(JSON)).build()
        val startCall = Http.client.newCall(start); token.attach(startCall)
        val uploadUrl = try {
            startCall.execute().use { r ->
                if (r.code !in 200..201) throw AiException("Gemini Files API Error ${r.code}")
                r.header("X-Goog-Upload-URL") ?: throw AiException("Gemini Files APIのアップロードURLを取得できませんでした")
            }
        } catch (e: IOException) {
            if (token.isCancelled) throw CancelledException()
            throw AiException("APIリクエスト中にエラーが発生しました")
        }
        val upload = Request.Builder().url(uploadUrl).header("x-goog-api-key", apiKey)
            .header("X-Goog-Upload-Offset", "0").header("X-Goog-Upload-Command", "upload, finalize")
            .post(body).build()
        val uploadCall = Http.client.newCall(upload); token.attach(uploadCall)
        return try {
            uploadCall.execute().use { r ->
                val raw = r.body.string()
                if (r.code !in 200..201) throw AiException("Gemini アップロードエラー ${r.code}")
                val f = JSONObject(raw).optJSONObject("file") ?: throw AiException("Gemini Files APIの応答にファイル情報がありません")
                val name = f.optString("name"); val uri = f.optString("uri")
                if (name.isEmpty() || uri.isEmpty()) throw AiException("Gemini Files APIの応答にファイル情報がありません")
                GeminiFile(name, uri, f.optString("state", "ACTIVE"))
            }
        } catch (e: IOException) {
            if (token.isCancelled) throw CancelledException()
            throw AiException("APIリクエスト中にエラーが発生しました")
        }
    }

    /** 音声は PROCESSING のことがあるため ACTIVE になるまで待つ（最大120秒） */
    fun waitActive(apiKey: String, file: GeminiFile, token: CancelToken): GeminiFile {
        var state = file.state
        val until = System.currentTimeMillis() + 120_000
        while (state == "PROCESSING" && System.currentTimeMillis() < until) {
            if (token.isCancelled) throw CancelledException()
            Thread.sleep(2000)
            try {
                get(apiKey, "$ROOT/v1beta/${file.name}", 30).use { r ->
                    if (r.code == 200) state = JSONObject(r.body.string()).optString("state", "ACTIVE")
                }
            } catch (_: IOException) {
            }
        }
        if (state == "FAILED") throw AiException("Geminiが音声の処理に失敗しました")
        return file
    }

    fun audioPart(file: GeminiFile, mime: String): JSONObject =
        JSONObject().put("file_data", JSONObject().put("file_uri", file.uri).put("mime_type", mime))

    fun textPart(text: String): JSONObject = JSONObject().put("text", text)

    /** 1 リクエストの JSONL を作ってアップロードし、バッチジョブを作成して `batches/xxx` を返す */
    fun createBatch(apiKey: String, model: String, parts: List<JSONObject>, thinkingLevel: String, displayName: String, token: CancelToken): String {
        val request = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray(parts))))
            .put("generation_config", JSONObject().put("thinking_config", JSONObject().put("include_thoughts", true).put("thinking_level", thinkingLevel)))
        val line = JSONObject().put("key", "request-1").put("request", request).toString()
        val bytes = line.toByteArray(Charsets.UTF_8)
        val jsonl = uploadFile(apiKey, bytes.toRequestBody("application/jsonl".toMediaType()), bytes.size.toLong(), "application/jsonl", "$displayName.jsonl", token)
        val body = JSONObject().put(
            "batch", JSONObject().put("display_name", displayName).put("input_config", JSONObject().put("file_name", jsonl.name))
        )
        val req = Request.Builder().url("$ROOT/v1beta/models/$model:batchGenerateContent")
            .header("x-goog-api-key", apiKey).header("User-Agent", Http.userAgent)
            .post(body.toString().toRequestBody(JSON)).build()
        val call = Http.client.newCall(req); token.attach(call)
        return try {
            call.execute().use { r ->
                val raw = r.body.string()
                if (r.code !in 200..201) throw AiException("Gemini Batch API Error ${r.code}")
                val name = JSONObject(raw).optString("name")
                if (!name.startsWith("batches/")) throw AiException("Gemini Batch APIの応答にジョブ名がありません")
                name
            }
        } catch (e: IOException) {
            if (token.isCancelled) throw CancelledException()
            throw AiException("APIリクエスト中にエラーが発生しました")
        }
    }

    private fun normalizeState(raw: String?): String {
        var s = (raw ?: "").uppercase()
        for (prefix in listOf("BATCH_STATE_", "JOB_STATE_")) if (s.startsWith(prefix)) s = s.removePrefix(prefix)
        return when (s) {
            "SUCCEEDED" -> "succeeded"
            "FAILED" -> "failed"
            "CANCELLED" -> "cancelled"
            "EXPIRED" -> "expired"
            else -> "running"
        }
    }

    /** ジョブの状態。通信失敗時は IOException / AiException */
    fun fetchState(apiKey: String, name: String): JobState {
        return get(apiKey, "$ROOT/v1beta/$name", 30).use { r ->
            if (r.code != 200) throw AiException("Gemini Batch API Error ${r.code}")
            val d = JSONObject(r.body.string())
            val meta = d.optJSONObject("metadata")
            val status = normalizeState(d.optString("state").ifEmpty { meta?.optString("state") })
            val dest = d.optJSONObject("dest") ?: meta?.optJSONObject("dest") ?: meta?.optJSONObject("output") ?: d.optJSONObject("response")
            val file = dest?.optString("responsesFile")?.ifEmpty { null }
                ?: dest?.optString("responses_file")?.ifEmpty { null }
                ?: dest?.optString("fileName")?.ifEmpty { null }
            val err = d.optJSONObject("error")?.optString("message") ?: ""
            JobState(status, file, err)
        }
    }

    fun downloadResults(apiKey: String, responsesFile: String): String =
        get(apiKey, "$ROOT/download/v1beta/$responsesFile:download?alt=media", 300).use { r ->
            if (r.code != 200) throw AiException("Gemini 結果ダウンロードエラー ${r.code}")
            r.body.string()
        }

    /** 結果 JSONL から thought / text / error を取り出す */
    fun parseResults(text: String): Results {
        val thought = StringBuilder(); val out = StringBuilder(); var error = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val obj = try { JSONObject(line) } catch (_: Exception) { continue }
            val errObj = obj.optJSONObject("error")
            if (errObj != null) {
                error = errObj.optString("message").ifEmpty { "リクエストが失敗しました" }
                continue
            }
            val parts = obj.optJSONObject("response")?.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.optBoolean("thought", false)) thought.append(p.optString("text", ""))
                else if (p.has("text")) out.append(p.optString("text", ""))
            }
        }
        return Results(thought.toString(), out.toString(), error)
    }

    fun cancel(apiKey: String, name: String) {
        val req = Request.Builder().url("$ROOT/v1beta/$name:cancel").header("x-goog-api-key", apiKey)
            .post("".toRequestBody(JSON)).build()
        try { Http.client.newCall(req).execute().close() } catch (_: IOException) {}
    }
}
