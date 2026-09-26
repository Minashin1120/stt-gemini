package com.minashin1120.voxcribe.ai

import com.minashin1120.voxcribe.data.SecretStore.KeyType

data class ModelOption(val value: String, val label: String)

/** Web版 app.py ALLOWED_MODELS / index.html #modelSelect と同じ一覧 */
object Models {
    const val DEFAULT = "gemini-3.6-flash"
    const val FALLBACK = "gemini-3.5-flash"

    val ALL = listOf(
        ModelOption("gemini-3.6-flash", "3.6 Flash"),
        ModelOption("gemini-3.5-flash", "3.5 Flash"),
        ModelOption("gemini-3.5-flash-lite", "3.5 Flash-Lite"),
        ModelOption("gemini-3-flash-preview", "3.0 Flash"),
        ModelOption("gemini-3.1-flash-lite", "3.1 Flash-Lite"),
        ModelOption("grok-stt", "Grok STT"),
        ModelOption("grok-live-transcribe", "Grok Live"),
        ModelOption("gpt-transcribe", "GPT-Trans."),
        ModelOption("gpt-live-transcribe", "GPT-Live"),
        ModelOption("whisper-1", "Whisper"),
    )

    val GEMINI = ALL.filter { it.value.startsWith("gemini") }

    val THINKING = listOf(
        ModelOption("MINIMAL", "Minimal (速い・思考プロセス非表示)"),
        ModelOption("LOW", "Low (標準)"),
        ModelOption("MEDIUM", "Medium (高品質)"),
        ModelOption("HIGH", "High (最高品質・遅い)"),
    )

    fun label(value: String) = ALL.firstOrNull { it.value == value }?.label ?: value

    fun validate(model: String?): String = if (ALL.any { it.value == model }) model!! else FALLBACK

    fun isLite(model: String) = model == "gemini-3.5-flash-lite" || model == "gemini-3.1-flash-lite"

    fun isGrok(model: String) = model == "grok-stt" || model == "grok-live-transcribe"

    fun isGrokLive(model: String) = model == "grok-live-transcribe"

    fun isOpenAi(model: String) = model == "gpt-transcribe" || model == "gpt-live-transcribe" || model == "whisper-1"

    fun isStt(model: String) = isGrok(model) || isOpenAi(model)

    fun keyType(model: String): KeyType = when {
        isOpenAi(model) -> KeyType.OPENAI
        isGrok(model) -> KeyType.XAI
        else -> KeyType.GEMINI
    }

    /** app.py get_thinking_level: LOW/MEDIUM/HIGH 以外は LOW（MINIMAL も LOW として送る） */
    fun apiThinkingLevel(value: String?): String {
        val v = (value ?: "LOW").uppercase()
        return if (v == "LOW" || v == "MEDIUM" || v == "HIGH") v else "LOW"
    }

    const val MAX_GEMINI_AUDIO_BYTES = 100L * 1024 * 1024
    const val MAX_XAI_AUDIO_BYTES = 500L * 1024 * 1024
    const val MAX_OPENAI_AUDIO_BYTES = 25L * 1024 * 1024

    fun maxBytes(model: String) = when {
        isGrok(model) -> MAX_XAI_AUDIO_BYTES
        isOpenAi(model) -> MAX_OPENAI_AUDIO_BYTES
        else -> MAX_GEMINI_AUDIO_BYTES
    }
}
