package com.minashin1120.voxcribe.ai

/**
 * app.py のプロンプト定数をそのまま移植したもの。
 * Web版の文言を変更した場合はここも同じ内容に更新すること。
 */
object Prompts {
    val VERBATIM_INSTRUCTION = """
STRICT INSTRUCTION:
1. Transcribe the audio exactly as spoken (Verbatim).
2. Do NOT use your internal knowledge to correct facts, dates, or years.
3. Output ONLY the text. No preamble.
4. Do NOT insert line breaks in the middle of a sentence, even if there is a pause in the speech. Only use line breaks at the end of a complete sentence or when the speaker/topic changes significantly.
"""

    val REPHRASE_AWARE_INSTRUCTION = """
STRICT INSTRUCTION WITH REPHRASE CORRECTION:
1. When the speaker starts a phrase, then immediately corrects it, treat the corrected wording as the final text.
2. Omit abandoned false starts and mistaken fragments that were clearly superseded by the correction.
3. Still transcribe ordinary speech verbatim when there is no self-correction.
4. Do NOT add explanations or notes. Output ONLY the final text.
"""

    val TEXT_REPHRASE_CORRECTION_PROMPT = """You are correcting a speech transcription for self-corrections (rephrasing).

STRICT RULES:
1. When the text shows the speaker started a phrase then immediately corrected it, keep only the corrected wording as the final text.
2. Omit abandoned false starts and mistaken fragments that were clearly superseded by the correction.
3. If there is no self-correction pattern, leave the text unchanged.
4. Do NOT change wording for style, grammar "improvement", facts, or polish. Only remove superseded fragments.
5. Preserve line breaks and punctuation of the kept text as much as possible.
6. Do NOT add explanations, labels, or notes. Output ONLY the final corrected text.

Transcription text:
"""

    val FILLER_REMOVAL_RULE = """
Additionally, remove filler words, hesitations, and filled pauses such as "えーと", "あー", "うー", "んー", "えっと", "あのー", "そのー", "まあ", "えー", "あっ", "あの", "その", "ええと", "あのう", "そのう", and similar non-lexical vocalizations from the transcription. Transcribe the remaining substantive speech naturally and coherently, minimizing any impact on the substantive content.
"""

    val LITE_OUTPUT_CORRECTION = """
Additionally, for this transcription:
1. Do NOT insert unnatural spaces in the Japanese text.
2. If the entire output lacks punctuation marks (such as "。" or "、"), add appropriate punctuation to improve readability. If any punctuation is already present, leave punctuation unchanged.
"""

    const val TRANSCRIBE_MODE_LABEL = "The user enabled rephrase correction mode for this transcription."
    const val REANALYZE_MODE_LINE = "MODE: The user enabled rephrase correction mode for this re-analysis."
    const val REANALYZE_BASE =
        "Listen again carefully and transcribe exactly.\nDo NOT insert line breaks in the middle of a sentence, even if there is a pause in the speech."

    /** index.html の「間隔修正」固定指示 */
    const val FIX_SPACING_INSTRUCTION =
        "以下の処理を順に実行してください：\n1. 不自然なスペース（空白）をすべて除去してください。単語間の適切なスペース（例：英単語の区切りなど）は保持してください。\n2. 句読点（。、）が一文も使われていないなど、句読点が完全に欠落している場合のみ、適切な句読点を補ってください。句読点が一部でも使われている場合は、句読点の修正は行わないでください。\n表記・言い回し・文体には一切変更を加えず、修正後のテキストのみを出力してください。説明や接頭辞・接尾辞は一切付けないでください。"

    fun yomigana(word: String) =
        "次の単語の読み方をひらがな（スペースなし）で答えてください。読み方だけを出力し、他の文章は含めないでください。\n単語: $word"

    /** app.py build_transcription_prompt */
    fun transcription(
        historyContext: String,
        wordListContext: String,
        modeLabel: String,
        rephrase: Boolean,
        filler: Boolean,
        lite: Boolean,
    ): String {
        var prompt = "$historyContext\n$wordListContext\n"
        var base = if (rephrase) REPHRASE_AWARE_INSTRUCTION else VERBATIM_INSTRUCTION
        if (filler) base += FILLER_REMOVAL_RULE
        if (lite) base += LITE_OUTPUT_CORRECTION
        prompt += if (rephrase) "MODE: $modeLabel\nTASK: $base" else "TASK: $base"
        return prompt
    }

    /** app.py /reanalyze のプロンプト */
    fun reanalyze(historyContext: String, wordListContext: String, rephrase: Boolean, filler: Boolean, lite: Boolean): String {
        var base: String
        val modeLine: String
        if (rephrase) {
            base = REPHRASE_AWARE_INSTRUCTION
            modeLine = REANALYZE_MODE_LINE
        } else {
            base = REANALYZE_BASE
            modeLine = ""
        }
        if (filler) base += FILLER_REMOVAL_RULE
        if (lite) base += LITE_OUTPUT_CORRECTION
        val parts = mutableListOf(historyContext, wordListContext)
        if (modeLine.isNotEmpty()) parts += modeLine
        parts += "TASK: $base"
        return parts.joinToString("\n")
    }

    /** app.py /improve の f-string（インデント・末尾スペースも同一） */
    fun improve(historyContext: String, wordListContext: String, text: String, instruction: String): String =
        "\n    $historyContext\n    $wordListContext\n    \n" +
            "    IMPORTANT: The text in \"Current Text\" is the result of manual corrections by the user. \n" +
            "    You MUST prioritize this \"Current Text\" as the definitive source for improvement, \n" +
            "    even if it differs from the earlier transcription in the history.\n\n" +
            "    Current Text: $text\n" +
            "    User Instruction: $instruction\n" +
            "    Task: Refine or transform the \"Current Text\" according to the \"User Instruction\". Output ONLY the final improved result.\n" +
            "    "
}
