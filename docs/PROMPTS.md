# 使用プロンプト一覧

本アプリが Gemini API に送るテキスト指示（プロンプト）の定義と組み立て方です。  
ソース上の定数・関数は主に `app/app.py` にあります。

> **注意**: Grok STT (`grok-stt`) はプロンプト非対応の純粋な STT API です。  
> 単語リストはサーバー側の文字列置換で適用し、言い直し修正・フィラー除去・Thinking は使えません。

---

## 目次

1. [プロンプトの全体構成](#1-プロンプトの全体構成)
2. [動的コンテキスト](#2-動的コンテキスト)
3. [文字起こしタスク指示（定数）](#3-文字起こしタスク指示定数)
4. [組み立て関数 `build_transcription_prompt`](#4-組み立て関数-build_transcription_prompt)
5. [再分析（Re-analyze）](#5-再分析re-analyze)
6. [テキスト改善（Improve）](#6-テキスト改善improve)
7. [間隔修正（Flash-Lite 用 UI）](#7-間隔修正flash-lite-用-ui)
8. [Grok STT との差分](#8-grok-stt-との差分)
9. [変更時の指針](#9-変更時の指針)

---

## 1. プロンプトの全体構成

Gemini への 1 リクエストは、概ね次の順で `parts` に載ります。

```text
[履歴コンテキスト]          ← get_active_history_context()
[カスタム単語リスト]        ← get_word_list_context()
[MODE 行（任意）]           ← 言い直し修正 ON のとき
TASK: [タスク指示]          ← VERBATIM / REPHRASE_AWARE (+ FILLER 任意)
[音声 inline_data]          ← base64 + mime_type
```

`generationConfig.thinkingConfig` で思考レベル（`LOW` / `MEDIUM` / `HIGH`）と `includeThoughts: true` を指定します。

---

## 2. 動的コンテキスト

### 2.1 履歴コンテキスト — `get_active_history_context(user_id)`

ユーザーの `retention_minutes` 以内の `History` を古い順に連結します。  
最後の操作から保持時間を超えている場合は **空文字**（コンテキストなし）。

**テンプレート（概念）:**

```text
--- CONTEXT: PREVIOUS INTERACTION HISTORY ---
[Action: {action_type}] ({HH:MM:SS})
Input/Instruction: {input_summary}
Model Thought: {thought_text}
Model Output: {result_text}
---------------------------------------------
...（複数件）

--- SAVED DATA (AVAILABLE AUDIO FILES) ---
- Saved Audio: {filename} (Uploaded: {HH:MM:SS})
------------------------------------------
```

- `action_type` 例: `transcribe`, `improve`, `reanalyze`
- 保存中の音声ファイル名も列挙し、「保存データが結果に影響しうる」ことをモデルに示します

### 2.2 カスタム単語リスト — `get_word_list_context(user_id)`

有効（`is_active=True`）な `WordSet` とその `Word` のみ。

```text
--- CUSTOM VOCABULARY (READING -> REPLACEMENT) ---
If you hear something similar to the reading on the left, strictly use the word on the right.
- {reading} -> {replacement}
...
--------------------------------------------------
```

有効セットが無い場合は空文字。

---

## 3. 文字起こしタスク指示（定数）

定義場所: `app/app.py`（`VERBATIM_INSTRUCTION` 付近）

### 3.1 一字一句モード（デフォルト）— `VERBATIM_INSTRUCTION`

ハルシネーション抑制・改行制御付きの厳密文字起こし。

```text
STRICT INSTRUCTION:
1. Transcribe the audio exactly as spoken (Verbatim).
2. Do NOT use your internal knowledge to correct facts, dates, or years.
3. Output ONLY the text. No preamble.
4. Do NOT insert line breaks in the middle of a sentence, even if there is a pause in the speech. Only use line breaks at the end of a complete sentence or when the speaker/topic changes significantly.
```

### 3.2 言い直し修正許可 — `REPHRASE_AWARE_INSTRUCTION`

話者が言い直したとき、後続の修正を正とし、捨てた言い始めを出さない。

```text
STRICT INSTRUCTION WITH REPHRASE CORRECTION:
1. When the speaker starts a phrase, then immediately corrects it, treat the corrected wording as the final text.
2. Omit abandoned false starts and mistaken fragments that were clearly superseded by the correction.
3. Still transcribe ordinary speech verbatim when there is no self-correction.
4. Do NOT add explanations or notes. Output ONLY the final text.
```

UI トグル `allow_rephrase_correction` が truthy のとき使用。  
このモード時は次の MODE 行も付与されます。

```text
MODE: The user enabled rephrase correction mode for this transcription.
```

（再分析時は `... for this re-analysis.`）

### 3.3 フィラー除去 — `FILLER_REMOVAL_RULE`

上記いずれかの指示の **末尾に連結**（独立トグル `allow_filler_removal`）。

```text
Additionally, remove filler words, hesitations, and filled pauses such as "えーと", "あー", "うー", "んー", "えっと", "あのー", "そのー", "まあ", "えー", "あっ", "あの", "その", "ええと", "あのう", "そのう", and similar non-lexical vocalizations from the transcription. Transcribe the remaining substantive speech naturally and coherently, minimizing any impact on the substantive content.
```

---

## 4. 組み立て関数 `build_transcription_prompt`

使用箇所: `/transcribe`、`/api/upload_complete` など。

```python
def build_transcription_prompt(
    history_context,
    word_list_context,
    mode_label,
    allow_rephrase_correction=False,
    allow_filler_removal=False,
):
    prompt = f"{history_context}\n{word_list_context}\n"
    if allow_rephrase_correction:
        base_instruction = REPHRASE_AWARE_INSTRUCTION
    else:
        base_instruction = VERBATIM_INSTRUCTION
    if allow_filler_removal:
        base_instruction += FILLER_REMOVAL_RULE
    if allow_rephrase_correction:
        prompt += f"MODE: {mode_label}\nTASK: {base_instruction}"
    else:
        prompt += f"TASK: {base_instruction}"
    return prompt
```

**組み合わせマトリクス**

| 言い直し | フィラー | 使用する TASK 本体 |
|:--------:|:--------:|-------------------|
| OFF | OFF | `VERBATIM_INSTRUCTION` |
| ON | OFF | `REPHRASE_AWARE_INSTRUCTION` + MODE 行 |
| OFF | ON | `VERBATIM` + `FILLER_REMOVAL_RULE` |
| ON | ON | `REPHRASE_AWARE` + `FILLER` + MODE 行 |

---

## 5. 再分析（Re-analyze）

エンドポイント: `POST /reanalyze`  
保存済みの最終音声を再送し、プロンプトを組み直します。

| 条件 | TASK 内容 |
|------|-----------|
| 言い直し **ON** | `REPHRASE_AWARE_INSTRUCTION`（+ 任意で FILLER）+ MODE 行 |
| 言い直し **OFF** | 短い再聴取指示（下記）+ 任意で FILLER |

**言い直し OFF 時の再分析専用 TASK（全文）:**

```text
Listen again carefully and transcribe exactly.
Do NOT insert line breaks in the middle of a sentence, even if there is a pause in the speech.
```

組み立てイメージ:

```text
{history_context}
{word_list_context}
[MODE: ... ]   # 言い直し ON のみ
TASK: {base_instruction}
```

---

## 6. テキスト改善（Improve）

エンドポイント: `POST /improve`  
ユーザーが結果テキストを手編集した内容を **最優先ソース** として扱わせます。

### 6.1 プロンプト全文（テンプレート）

```text
    {history_context}
    {word_list_context}
    
    IMPORTANT: The text in "Current Text" is the result of manual corrections by the user. 
    You MUST prioritize this "Current Text" as the definitive source for improvement, 
    even if it differs from the earlier transcription in the history.

    Current Text: {text}
    User Instruction: {instruction}
    Task: Refine or transform the "Current Text" according to the "User Instruction". Output ONLY the final improved result.
```

- `{text}` … テキストエリアの現在値（実行前に最新 History の `result_text` にも同期）
- `{instruction}` … UI の指示欄（例: 「要約して」）。最大長 `MAX_INSTRUCTION_LENGTH`（20000）
- 「音声も参照」ON の場合、続けて `Reference Audio:` + `inline_data` を parts に追加

### 6.2 モデル選択

`model == 'grok-stt'` のときは **自動的に `gemini-3.5-flash` にフォールバック**（Grok STT はテキスト改善不可）。

---

## 7. 間隔修正（Flash-Lite 用 UI）

フロントエンド `app/templates/index.html` の「間隔修正」ボタンは、`/improve` を固定指示で呼びます。  
3.1 Flash-Lite 利用時に不自然なスペースが入りやすいことへの対策です。

**固定 `instruction`（全文）:**

```text
以下の処理を順に実行してください：
1. 不自然なスペース（空白）をすべて除去してください。単語間の適切なスペース（例：英単語の区切りなど）は保持してください。
2. 句読点（。、）が一文も使われていないなど、句読点が完全に欠落している場合のみ、適切な句読点を補ってください。句読点が一部でも使われている場合は、句読点の修正は行わないでください。
表記・言い回し・文体には一切変更を加えず、修正後のテキストのみを出力してください。説明や接頭辞・接尾辞は一切付けないでください。
```

- `use_audio: false`
- 内部的には通常の Improve プロンプト（§6）にこの instruction が入る

---

## 8. Grok STT との差分

| 項目 | Gemini | Grok STT |
|------|--------|----------|
| システムプロンプト | 本ドキュメントの全文 | 送らない |
| 単語リスト | プロンプト注入 | 結果文字列に対するサーバー側置換 |
| 言い直し / フィラー | プロンプト | 無視（API 非対応） |
| Improve / 間隔修正 | 上記プロンプト | Gemini に切替 |

---

## 9. 変更時の指針

1. **Verbatim を緩めると** 日付・固有名詞の「補正」ハルシネーションが増えやすい → `VERBATIM_INSTRUCTION` の 1–2 は慎重に。
2. **改行ルール**（文中改行禁止）は長尺音声の読みやすさに直結。変更時は再分析パス（§5 の短い指示）も揃える。
3. **単語リスト**の文言を変える場合、Grok 側の置換ロジック（`process_grok_stt_background`）との意味の一貫性を確認する。
4. **Improve** の “Current Text 優先” は、手動修正が履歴の古い文字起こしに負けるバグ再発を防ぐために必須。
5. プロンプトを変更したら、短い録音・言い直しサンプル・フィラー多めサンプルで手動確認することを推奨。

---

## ソース対応表

| 項目 | ファイル | シンボル / 箇所 |
|------|----------|-----------------|
| Verbatim / Rephrase / Filler | `app/app.py` | `VERBATIM_INSTRUCTION`, `REPHRASE_AWARE_INSTRUCTION`, `FILLER_REMOVAL_RULE` |
| 組み立て | `app/app.py` | `build_transcription_prompt` |
| 履歴 / 単語コンテキスト | `app/app.py` | `get_active_history_context`, `get_word_list_context` |
| 文字起こし | `app/app.py` | `transcribe`, `upload_complete` |
| 再分析 | `app/app.py` | `reanalyze` |
| 改善 | `app/app.py` | `improve` |
| 間隔修正指示 | `app/templates/index.html` | `fixInstruction`（`btnFixSpacing` ハンドラ） |
