# settings — 設定画面

- `SettingsScreen.kt`: APIキー・保持期間・単語リスト・外観・プライバシー・全データ削除、カード共通部品。
- `ToolbarSettings.kt`: 通知専用のモデル・推論・言い直し修正・フィラー除去・ノイズ除去・形式・通知表示。バッテリー最適化除外の案内は `task/BatteryOptimization.kt`。設定は即時保存し、録音開始時にスナップショットを取得する。専用STTはプロンプトを受け付けないため修正・フィラー指定を無効化する。

`SettingsScreen` が `ToolbarSettings` を呼ぶ。通知処理は `task/RecordingToolbar.kt` に依存し、通常ワークスペースのモデル設定は変更しない。
