"""文字起こし用プロンプト定数とプロンプト組み立て。"""

import app as core


# プロンプト定数
VERBATIM_INSTRUCTION = """
STRICT INSTRUCTION:
1. Transcribe the audio exactly as spoken (Verbatim).
2. Do NOT use your internal knowledge to correct facts, dates, or years.
3. Output ONLY the text. No preamble.
4. Do NOT insert line breaks in the middle of a sentence, even if there is a pause in the speech. Only use line breaks at the end of a complete sentence or when the speaker/topic changes significantly.
"""

REPHRASE_AWARE_INSTRUCTION = """
STRICT INSTRUCTION WITH REPHRASE CORRECTION:
1. When the speaker starts a phrase, then immediately corrects it, treat the corrected wording as the final text.
2. Omit abandoned false starts and mistaken fragments that were clearly superseded by the correction.
3. Still transcribe ordinary speech verbatim when there is no self-correction.
4. Do NOT add explanations or notes. Output ONLY the final text.
"""

# テキストのみの言い直し修正（音声・履歴を送らない後処理用）
TEXT_REPHRASE_CORRECTION_PROMPT = """You are correcting a speech transcription for self-corrections (rephrasing).

STRICT RULES:
1. When the text shows the speaker started a phrase then immediately corrected it, keep only the corrected wording as the final text.
2. Omit abandoned false starts and mistaken fragments that were clearly superseded by the correction.
3. If there is no self-correction pattern, leave the text unchanged.
4. Do NOT change wording for style, grammar "improvement", facts, or polish. Only remove superseded fragments.
5. Preserve line breaks and punctuation of the kept text as much as possible.
6. Do NOT add explanations, labels, or notes. Output ONLY the final corrected text.

Transcription text:
"""

FILLER_REMOVAL_RULE = """
Additionally, remove filler words, hesitations, and filled pauses such as "えーと", "あー", "うー", "んー", "えっと", "あのー", "そのー", "まあ", "えー", "あっ", "あの", "その", "ええと", "あのう", "そのう", and similar non-lexical vocalizations from the transcription. Transcribe the remaining substantive speech naturally and coherently, minimizing any impact on the substantive content.
"""

LITE_OUTPUT_CORRECTION = """
Additionally, for this transcription:
1. Do NOT insert unnatural spaces in the Japanese text.
2. If the entire output lacks punctuation marks (such as "。" or "、"), add appropriate punctuation to improve readability. If any punctuation is already present, leave punctuation unchanged.
"""


def build_transcription_prompt(history_context, word_list_context, mode_label, allow_rephrase_correction=False, allow_filler_removal=False, is_lite_model=False):
    prompt = f"{history_context}\n{word_list_context}\n"
    if allow_rephrase_correction:
        base_instruction = REPHRASE_AWARE_INSTRUCTION
    else:
        base_instruction = VERBATIM_INSTRUCTION
    if allow_filler_removal:
        base_instruction += FILLER_REMOVAL_RULE
    if is_lite_model:
        base_instruction += LITE_OUTPUT_CORRECTION
    if allow_rephrase_correction:
        prompt += f"MODE: {mode_label}\nTASK: {base_instruction}"
    else:
        prompt += f"TASK: {base_instruction}"
    return prompt


def build_reanalyze_prompt(history_context, word_list_context, allow_rephrase_correction=False, allow_filler_removal=False, is_lite_model=False):
    if allow_rephrase_correction:
        base_instruction = REPHRASE_AWARE_INSTRUCTION
        mode_line = "MODE: The user enabled rephrase correction mode for this re-analysis."
    else:
        base_instruction = "Listen again carefully and transcribe exactly.\nDo NOT insert line breaks in the middle of a sentence, even if there is a pause in the speech."
        mode_line = ""
    if allow_filler_removal:
        base_instruction += FILLER_REMOVAL_RULE
    if is_lite_model:
        base_instruction += LITE_OUTPUT_CORRECTION
    prompt_parts = [history_context, word_list_context]
    if mode_line:
        prompt_parts.append(mode_line)
    prompt_parts.append("TASK: " + base_instruction)
    return "\n".join(prompt_parts)


def build_improve_prompt(history_context, word_list_context, text, instruction):
    # プロンプトを強化して手動修正を重視させる
    return f"""
    {history_context}
    {word_list_context}
    
    IMPORTANT: The text in "Current Text" is the result of manual corrections by the user. 
    You MUST prioritize this "Current Text" as the definitive source for improvement, 
    even if it differs from the earlier transcription in the history.

    Current Text: {text}
    User Instruction: {instruction}
    Task: Refine or transform the "Current Text" according to the "User Instruction". Output ONLY the final improved result.
    """
