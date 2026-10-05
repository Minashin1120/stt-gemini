# `static/js/index/` — メイン画面（`templates/index.html`）の JavaScript

もとは `index.html` にインラインで書かれていた約 3,000 行のスクリプトを、責務ごとに分割したものです。
ビルドツールは使わず、**古典 `<script src>`（ES モジュールではない）** を `index.html` の末尾に並べて読み込みます。

親ドキュメント: [../../README.md](../../README.md) · テンプレート: [../../../templates/README.md](../../../templates/README.md)

---

## 大前提（壊しやすいポイント）

1. **全ファイルが 1 つのグローバルスコープを共有する。** 関数・`let` / `const` はファイルをまたいでそのまま参照できる。`import` / `export` は使わない
2. **読み込み順が依存順。** `index.html` の `<script>` 並び（下表の順）を変えない。トップレベルで即時実行する文は宣言・リテラルの初期化だけにし、DOM を触る処理・イベント登録・初回ロードは各ファイルの `initXxx()` に入れて `main.js` から呼ぶ
3. **`DOMContentLoaded` 以降に初めて DOM 要素を取得する。** `canvas` `el` `dropArea` などの画面要素は `state.js` / `ui-mic.js` で `let` 宣言だけしておき、`initUiMic()` が代入する。関数の中では実行時に参照するため問題ないが、トップレベルで `el.xxx` を触らない
4. **グローバル名は一意にする。** 新しい関数・変数を足すときは `grep -rn "名前" static/js/index` で衝突を確認する（`window` の既存プロパティ名 `status` `name` `open` `stop` なども不可）
5. HTML の `onclick="..."` から呼ぶ関数は `window.xxx = ...` で公開する（`wordsets.js` `history.js`）。テンプレート側の `onclick` 名を変えるときは併せて直す
6. スクリプト URL は `static_v('js/index/xxx.js')`（更新時刻付き）で出力するため、キャッシュバスターを手で上げる必要はない

---

## ファイル一覧（読み込み順）

| # | ファイル | 責務 | 主な関数 |
|---|----------|------|----------|
| 1 | `common.js` | トースト、HTML エスケープ、CSRF / JS チャレンジ付き `fetch` | `showToast` `escapeHtml` `csrfFetch` |
| 2 | `wordsets.js` | 単語セット（有効化モーダル・管理モーダル・読み仮名自動生成） | `loadWordSetStatus` `toggleWordSet` `openWordSetManageModal` `refreshManageList` |
| 3 | `apikey.js` | API キー未設定モーダル（入力・モデル切替・録音の破棄確認・音声ダウンロード） | `ensureApiKeyForModel` `showApiKeyModal` `closeApiKeyModal` |
| 4 | `state.js` | 録音・アップロード・Grok Live など画面全体で共有する状態変数（宣言のみ） | `isRecording` `abortController` `grokLive*` |
| 5 | `audio-dsp.js` | 録音 PCM の正規化（ゲイン・ソフトリミッタ）と WAV / MP3（lamejs）エンコード | `encodeNormalizedRecording` `encodeWavBlob` `encodeMp3Blob` |
| 6 | `mic.js` | マイク取得。内蔵マイクの `deviceId` 固定、ノイズ除去 OFF の exact 制約、実効値の検証 | `acquireMicStream` `buildMicConstraintAttempts` `assertMicProcessingVerified` |
| 7 | `capture.js` | AudioWorklet による PCM 収集グラフの構築/破棄、波形と入力レベル表示 | `createPcmCaptureNode` `teardownCaptureGraph` `startVisualizer` |
| 8 | `ui-mic.js` | 画面要素の取得（`el` など）、マイク事前準備 UI | `prepareMic` `releasePreparedMic` `initUiMic` |
| 9 | `transfer.js` | 通信エラー時のローカル音声保持、アップロード進捗 UI、進捗付き FormData 送信 | `sendFormDataWithProgress` `showUploadProgress` `downloadLocalAudio` |
| 10 | `settings.js` | モデル・推論レベル・後処理設定の保存/復元（`localStorage`）、カスタムドロップダウン、ファイル選択 | `getModel` `setModel` `handleFileSelect` |
| 11 | `history.js` | 履歴の読み込み、実行中タスクへの再接続、ファイルマネージャ（並列 DL） | `loadHistory` `checkRunningTasks` `parallelDownload` |
| 12 | `stream.js` | SSE ストリームの受信と表示、単一/チャンク並列アップロード | `handleStreamResponse` `uploadFile` `uploadFileChunked` |
| 13 | `grok-live.js` | Grok Live（WebSocket リアルタイム文字起こし）のクライアント | `startGrokLiveSession` `finishGrokLiveSession` |
| 14 | `recording.js` | 録音の開始・一時停止・停止のハンドラ | `rec` `initRecording` |
| 15 | `actions.js` | 録音データの送信（`upl` / `finalizeLiveGrok`）、再分析・AI 改善・間隔修正・言い直し修正・削除・コピーのボタン | `upl` `finalizeLiveGrok` `syncPostprocessButtons` |
| 16 | `batch.js` | Batch（Gemini Batch API）: 「Batchで実行」トグル、投入 `submitBatchJob`、60 秒ポーリング、完了時の取り込み確認ダイアログ、`/batch` 画面の一覧描画。**`batch.html` でも読み込む**ため、`el` など index 専用グローバルは `typeof` で存在確認して使う | `isBatchMode` `submitBatchJob` `importBatchJob` `initBatch` |
| 17 | `main.js` | `DOMContentLoaded` で各 `initXxx()` を元の実行順どおりに呼ぶ | — |

`initXxx()` を持つのは `ui-mic` `transfer` `settings` `history` `stream` `recording` `actions` `batch` です。呼び出し順は
`main.js` に書かれており、**イベント登録の順序**に依存する処理があるため入れ替えないでください
（例: `settings.js` の初期化は `el` を必要とするので `initUiMic()` の後）。

---

## 変更するときの目安

| やりたいこと | 見るファイル |
|--------------|--------------|
| 録音の音質・ゲイン・エンコード | `audio-dsp.js` |
| マイクが取れない / ノイズ除去 OFF が効かない | `mic.js`（先に [templates/README.md](../../../templates/README.md) の「編集時の注意」） |
| 波形・レベルメーター | `capture.js` |
| モデル一覧・推論レベル・保存キー（`stt_m` `stt_t_*`） | `settings.js` |
| SSE の表示・思考/結果の分離 | `stream.js` |
| 大容量アップロード | `stream.js`（`uploadFileChunked`）と `transfer.js` |
| 単語セット UI | `wordsets.js`（サーバー側 HTML は `templates/partials/_word_sets.html`） |
| Batch 投入・取り込み・一覧 | `batch.js`（サーバー側は `app/batch.py` `app/routes_batch.py`） |
| 「間隔修正」の固定指示 | `actions.js`（`fixInstruction`、全文は [docs/PROMPTS.md](../../../../docs/PROMPTS.md)） |

## ファイルを増やす / 肥大したとき

1 ファイルが 500 行を超えたら責務で分割し、`index.html` の `<script>` 並び（依存順）・この表・`main.js` を更新します。
チェックは `python3 scripts/check_code_size.py`（詳細は [AGENTS.md](../../../../AGENTS.md)）。

## テスト

`tests/test_security.py` は `index.html` と、その中の `static_v('js/index/*.js')` が指す各ファイルを連結して
文字列アサーションを行います。マイク取得・録音停止まわりの安全条件（exact 制約、`applyConstraints` を使わない、
検証失敗時に録音を始めない）が守られていることを確認しています。
