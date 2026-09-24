# `app/android/` — Android 版 Voxcribe

Web 版（`app/`）と同じ画面・機能・文言を持つネイティブ Android アプリです。

- **Kotlin + Jetpack Compose**（AGP 9 / Gradle 9、minSdk 29）
- **サーバーとは通信しない**スタンドアロン版。端末から Gemini / xAI / OpenAI の API を直接呼び出します。
- 履歴・保存音声・単語セット・設定は端末内（SQLite / SharedPreferences）に保存し、API キーは Android Keystore の AES-GCM 鍵で暗号化して保存します。
- 録音と文字起こしはフォアグラウンドサービスで継続します（画面 OFF・バックグラウンドでも停止しない）。

## ビルド

**ローカルでは絶対にビルドしないでください**（サーバーの RAM が 2GB のため）。

| Workflow | トリガー | 成果物 |
|---|---|---|
| `.github/workflows/android.yml`（Android CI） | `app/android/**` を変更して main へ push / 手動実行 | artifact `app-debug`（debug APK） |
| `.github/workflows/release.yml`（Release Build） | `v*` タグの push / 手動実行 | artifact `app-release-signed` と GitHub Release |

- 署名は固定鍵 `ci/debug.keystore`（初回のみ CI が生成してコミット）。詳細はリポジトリ直下の `android-build.md`。
- ビルド成功後、古い APK artifact は自動で削除され、最新の APK だけが残ります。
- ビルドに失敗すると、コンパイルエラーがジョブのアノテーションに出力されます。

## Android 版だけの仕様

| 項目 | 内容 |
|---|---|
| 録音 | `AudioManager` で内蔵マイク数を検出し、**2つ以上ならステレオ（両マイク）で録音**。停止時に `(L+R)×0.5` でモノラル統合し、Web 版と同じレベル補正 → MP3(192kbps)/WAV でエンコード |
| ノイズ除去 | ON: `MIC` ソース + NoiseSuppressor / AGC。OFF: `UNPROCESSED`（非対応端末は `MIC` + エフェクト無効） |
| 認証 | ログイン・登録・ロック解除は無し（初回起動時のみ Welcome を表示） |
| 危険な操作 | 「アカウント削除」の代わりに「端末内データをすべて削除」 |
| GPT-Live | `ffmpeg` の代わりに MediaCodec で 24kHz モノラル PCM に変換 |

## ソース構成（`app/src/main/java/com/minashin1120/voxcribe/`）

| パス | 役割 | Web 版の対応箇所 |
|---|---|---|
| `ai/Prompts.kt` | プロンプト定数 | `app.py` の `VERBATIM_INSTRUCTION` 等 |
| `ai/Models.kt` | モデル・推論レベル一覧、サイズ上限 | `ALLOWED_MODELS` 等 |
| `ai/AiRunner.kt` | 文字起こし / 再分析 / 改善 / 言い直し修正、履歴コンテキスト、単語置換、履歴保存 | `app.py` の各ルートとバックグラウンド処理 |
| `ai/GeminiClient.kt`・`ai/SttClients.kt` | Gemini（SSE）・xAI STT・OpenAI（SSE / Realtime WebSocket） | `process_*_background` |
| `audio/` | マイク検出・録音・モノラル統合・正規化・エンコード | `index.html` の録音処理 |
| `data/` | SQLite（履歴・単語セット）・設定・暗号化キー・音声保存 | MariaDB / `uploads/` / localStorage |
| `task/` | フォアグラウンドサービス、保持時間による自動削除 | Redis タスク / `cleanup_old_data` |
| `ui/` | 画面（Welcome / ワークスペース / 設定）、9 テーマ | `templates/`・`static/css/style.css` |
