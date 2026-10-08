# Android アプリ内更新

- `UpdateChecker.kt`: GitHub Releases の最新版・APK URL・サイズを取得し、公開版のバージョンを比較する。
- `UpdateController.kt`: 起動時の確認、ダイアログ状態、ダウンロード開始・進捗・エラーを管理する。
- `ApkDownloader.kt`: APK 専用 HTTP クライアントで取得する。HEAD でリダイレクト先・サイズ・Range 対応・強い ETag を確認し、4MB 以上は HTTP/1.1 の4接続で別範囲を同じ一時ファイルへ保存する。全範囲の完了後に APK 名へ変更する。

依存は `UpdateController` → `UpdateChecker` / `ApkDownloader`。画面は `ui/update/UpdateDialog.kt`、インストールは `MainActivity.kt` が担当する。Web版には対応機能がない。

進捗は各接続の受信ごとに同期して集計・更新し、時間による間引きはしない。コピー用バッファも従来どおり64KB。通常取得の出力のみ64KBのバッファ付きにし、小さい読み取りごとのファイル書き込みをまとめる。

Range 非対応・HEAD 失敗・不正な範囲応答の場合は通常取得へ戻す。キャンセル時は接続を閉じ、全ワーカー終了を待つため、フォールバックと範囲書き込みが競合しない。サイズ不一致・途中切断は成功扱いにせず、一時ファイルを削除する。AI 通信用のクライアント設定は変更しない。

`app/src/test/.../update/ApkDownloaderTest.kt` はローカル HTTP サーバーで並列取得、通常取得への切り替え、途中切断、キャンセルを検証する。Android のビルド・テストは GitHub Actions のみで実行する。
