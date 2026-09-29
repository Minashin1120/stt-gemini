# `ui/workspace/` — ワークスペース画面（Web 版 `index.html` 相当）

録音・アップロード・結果表示・改善・履歴・単語セット・API キー確認を扱う画面と、そのロジックです。
Web 版の対応先は `app/static/js/index/*.js`（[README](../../../../../../../../../../static/js/index/README.md)）と `app/templates/index.html` です。

## 構成

### 画面（Compose）

| ファイル | 内容 |
|----------|------|
| `WorkspaceScreen.kt` | 画面本体（入力カード・録音/アップロード・結果・改善カード） |
| `Dialogs.kt` | モーダル群（削除確認・保存データ・単語セット・API キー） |
| `Visualizer.kt` | 波形と入力レベル表示 |

### ロジック（`WorkspaceController`）

`WorkspaceController` は Application スコープで 1 つ保持され、**状態は `WorkspaceController.kt` に集約**し、
処理は用途別ファイルの **拡張関数**（`fun WorkspaceController.xxx()`）として書いています。
呼び出し側（`ctrl.rec(...)` など）は分割前と同じ書き方のままで、同じパッケージなので import も不要です。

| ファイル | 内容 | Web 版の対応 |
|----------|------|--------------|
| `WorkspaceController.kt` | データクラス（`StatusView` `UploadPanel` `ApiKeyPrompt` …）、状態（`mutableStateOf`）、設定変更、画面開始処理 | `state.js` `settings.js` |
| `WorkspaceRecording.kt` | マイク準備、録音の開始/停止/一時停止/キャンセル、Grok Live、録音データの送信（`upl` / `finalizeLiveGrok`） | `ui-mic.js` `recording.js` `grok-live.js` `actions.js` |
| `WorkspaceUpload.kt` | ファイル選択、アップロード、アップロード進捗 UI | `settings.js`（`handleFileSelect`）`stream.js` |
| `WorkspaceTask.kt` | AI 処理の実行（`startTask`）、結果のストリーム表示、中断 | `stream.js`（`handleStreamResponse`） |
| `WorkspacePostprocess.kt` | 再分析・改善・間隔修正・言い直し修正、コピー、音声削除、一括削除 | `actions.js` |
| `WorkspaceFiles.kt` | 履歴、保存データ（ファイルマネージャ）、エラー時のローカル音声ダウンロード | `history.js` `transfer.js` |
| `WorkspaceWordSets.kt` | 単語セットの有効化・管理・読み仮名生成 | `wordsets.js` |
| `WorkspaceApiKey.kt` | API キー未設定モーダルの制御（`ensureApiKeyForModel`）、全データ削除 | `apikey.js` |

## 規約（壊しやすいポイント）

1. **状態（`var ... by mutableStateOf` やフィールド）は `WorkspaceController.kt` にだけ置く。** 拡張関数はフィールドを持てない
2. 拡張関数から触る必要があるため、元は `private` だった状態・ヘルパーは **`internal`**（`internal set`）にしてある。
   新しい状態を足すときも、ほかのファイルから使うなら `internal` にする
3. 拡張関数を足した場合、`private` を付けてもそのファイル内でしか使えない。他ファイルから呼ぶなら `internal` か public にする
4. 内部列挙型 `BeginOn` は `WorkspaceTask.kt` にある（`startTask` の引数）
5. Web 版に機能を足したら、対応表の js ファイルと同じ責務のファイルにここでも入れる（`AGENTS.md` の対応表を参照）
6. 1 ファイルが 500 行を超えたら責務で分割し、この表を更新する。`WorkspaceScreen.kt`（約 670 行）は Compose の画面で、
   肥大したら `InputCard` / `ResultCard` などの単位でファイルを分ける

> このディレクトリはローカルでビルドしません（RAM 制約）。変更は push して GitHub Actions のビルド結果で確認します。
