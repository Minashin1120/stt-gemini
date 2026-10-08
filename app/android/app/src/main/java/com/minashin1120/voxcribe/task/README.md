# task — バックグラウンド処理と通知

- `WorkService.kt`: 通常のワークスペースの録音・AI処理を維持する。
- `RecordingToolbar.kt`: 通知専用の状態、録音、コピー、アプリへの一時停止状態の引き継ぎ。マイクは workspace の `NativeRecorder` を共有し、同時使用を防ぐ。`AiRequest.Transcribe(transient = true)` で履歴コンテキストを注入せず、履歴・保存音声・lastAudio・結果UIを更新しない。音声はキャッシュだけで扱い、完了・失敗・破棄時に削除する。再コピー用テキストはメモリだけに保持する。
- `ToolbarService.kt`: 通知録音中は microphone、文字起こし中は dataSync のサービス。待機時はサービスを停止して通常通知を残す。通知IDは通常の WorkService と異なる。
- `ToolbarCommandReceiver.kt`: 通知の再コピーを画面表示なしで実行する。録音開始は `PendingIntent.getForegroundService` で `ToolbarService` を直接起動する（通知操作によるmicrophoneサービス開始の例外）。通知タップにアプリを開くintentを付けず、「停止してアプリで継続」のみMainActivityへ送る。
- `BatteryOptimization.kt`: アプリ初回起動時に一度だけ表示する最適化除外案内と、設定画面からの再案内。Android標準の最適化除外ダイアログを開く。通知操作からは呼ばない。
- `ToolbarRestoreReceiver.kt`: 端末再起動・アプリ更新後の待機通知の復元。
- `RetentionCleaner.kt`: 保存済み音声・履歴の保持期間管理。
- `BatchWork.kt`: Batchジョブの完了確認。

通知設定は `data/Prefs.kt` と `ui/settings/ToolbarSettings.kt`。アプリ継続は `MainActivity` → `RecordingToolbar.handoff` → workspaceの一時停止UIを使う。通知録音からの引き継ぎでは Grok のライブ表示や通常の結果保存経路に入れない。通知の停止・破棄は録音準備中には受け付けない（破棄は準備完了を待つ）。Androidの通知許可・チャンネル無効化・OSによる強制停止では通知表示が制限される。
