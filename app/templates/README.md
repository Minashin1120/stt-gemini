# `app/templates/` — UI テンプレート

Jinja2 + Bootstrap 5.3 + Vanilla JavaScript です。ビルドツールは使いません。

親ドキュメント: [../README.md](../README.md) · プロンプト: [../../docs/PROMPTS.md](../../docs/PROMPTS.md)

---

## ファイル一覧

| ファイル | 役割 |
|----------|------|
| `base.html` | 共通レイアウト、ナビ、テーマ IIFE（`localStorage.app_theme`）、トースト土台 |
| `welcome.html` | 未ログイン向け紹介 |
| `login.html` | ログイン（Turnstile / ハニーポット / JS チャレンジ） |
| `register.html` | 新規登録 |
| `request_unlock.html` | アカウントロック解除申請 |
| `settings.html` | Gemini / xAI API キー、保持時間、テーマ保存 |
| `index.html` | **メイン画面**（録音・アップロード・結果・改善・履歴・単語セット） |
| `partials/_word_sets.html` | 単語セット管理 UI の部分テンプレート |

---

## `index.html` の主な責務

フロントのビジネスロジックの大半がここにあります（単一 HTML 内の `<script>`）。

### 音声入力

- **録音タブ**: `getUserMedia`、モバイルの内蔵マイク `deviceId` 厳密指定、初回取得時の必須音声処理制約、AudioWorklet録音、波形・実入力レベル、MP3（lamejs）/ 真の PCM WAV エンコード
- **アップロードタブ**: ドラッグ＆ドロップ、100MB 超はチャンク並列アップロード
- **新規 / 追加**: 新規は履歴・保存音声をクリアしてから送信、追加は結果を追記

### モデル・モード

- モデル選択: 3.5 Flash / 3.0 Flash / 3.1 Flash-Lite / Grok STT（`localStorage` キー `stt_m`）
- 推論レベル: LOW / MEDIUM / HIGH（Grok 選択時は無効化）
- 言い直し修正・フィラー除去トグル（両タブ共通表示、キー `stt_fl` 等）

### ストリーミング

- `csrfFetch` + `AbortController`（処理停止ボタン）
- SSE を `handleStreamResponse` で処理し、思考 / 結果を分離表示
- ページロード時 `checkRunningTasks()` で Redis 上の実行中タスクに再接続

### AI 改善・間隔修正

- 指示入力 → `POST /improve`
- **間隔修正**ボタン: Flash-Lite 向け。固定の日本語 instruction で `/improve` を呼ぶ  
  → 全文は [docs/PROMPTS.md §7](../../docs/PROMPTS.md#7-間隔修正flash-lite-用-ui)

### その他 UI

- API キー未設定モーダル（Liquid Glass 風、リロードなしで保存して続行）
- 履歴コピー、個別削除、ファイルマネージャ（大容量は並列 DL）
- 単語セットモーダル（`/api/word_sets/manage_html` 等）

---

## テーマ連携

`base.html` が描画前に:

```js
document.documentElement.setAttribute('data-theme', localStorage.getItem('app_theme') || 'default');
```

スタイル定義は [../static/css/style.css](../static/css/style.css)（[static/README.md](../static/README.md)）。

---

## セキュリティ用フロント要素

ログイン・登録フォームなど:

- Cloudflare Turnstile ウィジェット
- ハニーポット欄（`aria-hidden` / `tabindex="-1"`）
- `_js_challenge` トークン（`csrfFetch` が API にも付与）

---

## 編集時の注意

1. `index.html` は行数が多いため、録音（`rec` / `vis`）、アップロード、SSE、改善のブロックを意識して変更する
2. 識別子の重複（過去に `stream` 名衝突で SyntaxError）に注意
3. モバイル Chrome のマイク制約は歴史的に挙動が変わりやすい。OFFは取得後の `applyConstraints` ではなく、初回 `getUserMedia` の exact 制約で録音プリセットを決めること
4. モバイルのマイクは許可後に `enumerateDevices()` で「内蔵」候補を探し、`deviceId: { exact: ... }` で再取得する。`default` は外部機器へ切り替わり得るため固定先として扱わない
5. 実機確認では「内蔵マイク: 固定確認」「Chrome処理: OFF確認」バッジ、入力dBFS、実ファイル、小声の文字起こしを確認すること。`getSettings()` は端末メーカーの前段DSPまでは証明しない
6. プロンプト定数の大半は **サーバー側**。フロントで持つ固定文は間隔修正の `fixInstruction` のみ（現状）
