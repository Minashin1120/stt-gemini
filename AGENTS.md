## 構成
このリポジトリには2つのクライアントがあります。

| 区分 | 場所 | 内容 |
|------|------|------|
| Web版 | `app/`（`app.py`・`templates/`・`static/`） | Flask サーバー。ブラウザから利用。サーバー側で Gemini / xAI / OpenAI を呼び出す |
| Android版 | `app/android/` | Kotlin + Jetpack Compose のネイティブアプリ。**サーバーとは通信しない**スタンドアロン版で、端末から各AIのAPIを直接呼び出し、履歴・音声・単語セット・APIキーはすべて端末内に保存する |

Android版はWeb版と同じUI・機能・文言を持つように作られています。Android版だけの仕様は次の2点です。
- 録音: 内蔵マイク数を検出し、2つ以上ならステレオで両マイクから録音し、停止時にモノラル統合する（`audio/`）。
- ログイン・登録・ロック解除画面は無い（アカウントはサーバーにしか存在しないため）。

## 変更対象の判断（Web版 / Android版）
- 変更は**基本的にWeb版とAndroid版の両方**に入れます。
- プロンプトで「Web版のみ」「アプリ版（Android版）のみ」などと明示されている場合は、そちらだけを変更します。どちらとも読める場合は両方に入れます。
- Web版の変更をAndroid版へ移植するときの対応先:

| Web版 | Android版 |
|------|------|
| `app/prompts.py` のプロンプト定数・改善プロンプト（`routes_transcribe.py` の `improve`） | `app/android/.../ai/Prompts.kt` |
| `app/app.py` の `ALLOWED_MODELS`・モデル選択肢・推論レベル | `ai/Models.kt` |
| `routes_transcribe.py`・`routes_files.py` の文字起こし・再分析・改善、`app.py` の履歴コンテキスト・単語置換、`streaming.py`・`processors*.py` | `ai/AiRunner.kt`（Gemini/xAI/OpenAI 呼び出しは `ai/GeminiClient.kt`・`ai/SttClients.kt`） |
| `templates/index.html` の画面、`static/js/index/*.js` のロジック | `ui/workspace/`（画面は `WorkspaceScreen.kt`・`Dialogs.kt`、ロジックは `WorkspaceController*.kt`。対応表は各 README） |
| `templates/settings.html` | `ui/settings/SettingsScreen.kt` |
| `templates/welcome.html` | `ui/welcome/WelcomeScreen.kt` |
| `static/css/*.css`（`themes.css` など）のテーマ・色 | `ui/theme/Themes.kt`・`ui/common/` |
| 録音の正規化・エンコード（`static/js/index/audio-dsp.js`） | `audio/Finalize.kt` |

## Git管理
このワークスペースは `/home/stt-gemini` をルートにした `git` リポジトリとして扱います。

- 追跡対象はアプリ本体のソースコード、テンプレート、静的ファイル、`scripts/`（開発用スクリプト）、`tests/`、`docs/`、Android版（`app/android/`）、GitHub Actions（`.github/`）、`.gitignore` とします。
- `app/venv/`、`app/.env/`、`app/*.log/`、`app/uploads/`、`.codex/`、`.gemini/`、`cookies.txt`、`test.mp3`、`引き継ぎ資料.txt`、個人用シェル設定、Androidのビルド生成物（`app/android/.gradle/`・`app/android/**/build/`・`local.properties`）はコミットしません。
- `.gitignore` への追記は自由に行って構いませんが、既存のエントリを削除・変更して追跡対象を広げないでください。
- 編集後は `git status` で状態を確認し、意図しない生成物が含まれていないかを点検してください。
- 新しく追跡したいファイルがある場合は、先に `.gitignore` を見直してから追加します。
- コミットする場合は、変更内容が読み取れる短いメッセージを付けます。
- コミットした後は、必ずGitHubへpushを行い、リモートリポジトリを最新の状態に保ちます。
- GitHub Actions がリポジトリへコミットすることがある（初回の `ci/debug.keystore`）ため、push 前に `git pull --rebase origin main` で最新化してください。

## Android版のビルド（厳守）
- **Androidのビルドはローカルで絶対に実行しないでください。** サーバーのRAMが2GBしかなく、ビルドするとサーバー（Web版の本番）が落ちます。
  - 禁止: `./gradlew`・`gradle`・`assemble*`・`bundle*`・`lint`・`test` 等のGradleタスク、`sdkmanager`、エミュレーター、Kotlinコンパイラの実行。
- ビルドはすべて GitHub Actions に委託します。`app/android/` を変更して push すると `.github/workflows/android.yml`（Android CI）が debug APK をビルドします。`v*` タグの push で `release.yml` が署名済み release APK と GitHub Release を作成します。
- 結果の確認は GitHub の Actions 画面、または `curl https://api.github.com/repos/Minashin1120/stt-gemini/actions/runs` で行い、ビルド失敗時はログを見て修正し再度 push します。
  - `~/.github_pat` にファイルが存在する場合は、それを使って認証付きで叩く（`curl -H "Authorization: Bearer $(cat ~/.github_pat)" ...`）。未認証だと60回/時のレート制限にすぐかかり、ジョブの生ログ（`/actions/jobs/{id}/logs`）も取得できないため。このファイルはこのサーバー上にのみ置かれた個人用PAT（fine-grained、対象リポジトリのみ・Actions/Contents Read-only）で、**値をコミットしたり出力・記載したりしない**こと。存在しない場合は未認証のまま（読み取り専用APIのみ）で構わない。
- 各ビルド成功後、過去の APK artifact（`app-debug` / `app-release-signed`）は workflow が自動削除します（Releases は残す）。
- 署名鍵のルール（詳細は `android-build.md`）:
  - 固定鍵 `app/android/ci/debug.keystore` を再生成・置換・削除しない。GitHub Secrets に署名鍵を保存しない。
  - `applicationId`（`com.minashin1120.voxcribe`）を変更しない。
  - keystore の SHA-1/SHA-256 が Actions ログで毎回同じであることを確認する。

## Android版のリリース
- リリース（GitHub Release の作成）は**自動化されています**。`main` に `app/android/**`（または `.github/workflows/android.yml`）を含む push があり、Android CI（debug ビルド）が成功すると、`android.yml` の `auto_release` ジョブが自動で次のパッチバージョンのタグを作成・push し、`release.yml`（署名済み release APK のビルドと GitHub Release 作成）を起動します。ユーザーの明示的な指示は不要です。
- タグは `vX.Y.Z` 形式（例 `v1.0.0`）。既存タグ（`v[0-9]*.[0-9]*.[0-9]*`）の最大値からパッチ番号を1つ上げて自動決定します（既存タグが無ければ `v1.0.0`）。既存タグの削除・付け替えはしません。
- バージョンは `app/android/app/build.gradle.kts` が自動で決めるため、手で書き換えません。
  - `versionCode`: ビルド時刻（2026-01-01 UTC からの経過分）。debug・release の両方で単調増加する。
  - `versionName`: release はタグから `X.Y.Z`（`release.yml` が `RELEASE_TAG` で渡す）、debug は `0.0.<run番号>-debug`。
- 確認手順:
  1. `curl https://api.github.com/repos/Minashin1120/stt-gemini/actions/workflows/release.yml/runs` で成功を確認し、`/releases` に該当タグの Release と `app-release.apk` が添付されていることを確認する
  2. 失敗した場合はログを見て修正を `main` に push する（再度 `auto_release` が走り、新しいタグで再実行される）。緊急時は Actions 画面から `Release Build` を `tag_name` 指定で手動実行してもよい（`workflow_dispatch` 入力）。
- マイナー・メジャーバージョンを上げたい場合や、意図せず自動リリースしたくない変更（ドキュメントのみ等）を push する場合は、事前にユーザーに確認すること。

## コード肥大の管理（AIエージェントのコンテキスト対策）
1 ファイルが大きいと、読むだけでコンテキストを圧迫し、無関係なコードが混ざって精度が落ちます。次のルールで肥大を防ぎます。

- **判定はスクリプトで行い、モデルはソースを読んで判断しません。** `python3 scripts/check_code_size.py` を実行します（出力は数行。問題が無ければ `OK` の 1 行だけ）。
  - `FAIL`（1 ファイル 800 行超 **または** 40 KB 超）: 分割必須。終了コード 1
  - `WARN`（500 行超 **または** 28 KB 超）: 分割を検討。次の機能追加で FAIL に届く水準。既に WARN のファイルへ大きく足す場合は先に分ける
  - 対象は `.py` `.kt` `.js` `.html` `.css` など（フォント・ビルド生成物・`.claude/` などは除外）。しきい値は同スクリプト冒頭の定数
- **実行するタイミング**: ソースを変更したとき、コミット前（「変更時の手順」の 2）に必ず 1 回。ファイルを新しく大きく書いたときも実行する。
- **FAIL が出たら分割してから**コミットします（分割しない理由が無い限り、そのまま push しない）。
  1. 責務（機能・画面・レイヤ）で分ける。行数合わせの機械的な分割はしない
  2. 挙動を変えない（移動のみ）。Web 版は `python -m unittest discover -s tests` と `python -m pyflakes app/*.py`（`undefined name` が無いこと）、JS は `node --check` で確認する。Android 版はローカルビルド禁止なので、`private`→`internal` などの可視性と import を目視で確認し、CI の結果で確かめる
  3. **分割した先のディレクトリの `README.md` を作成/更新する**（ファイルごとの責務・読み込み順や依存の向き・触るときの注意）。README が無いディレクトリに分割ファイルを置かない
  4. 上の「変更対象の判断」の対応表と、影響する他の README（`app/README.md` `app/templates/README.md` `app/static/README.md` `docs/PROMPTS.md` `app/android/README.md` など）の参照先も更新する
  5. 分割後に `python3 scripts/check_code_size.py` で `FAIL` が消えたことを確認する
- 分割の前提として、現在は次の構造になっています（詳細は各 README）:
  - Web バックエンド: `app/app.py`（ハブ）＋ `routes_*.py` / `streaming.py` / `processors*.py` / `prompts.py` → `app/README.md`。**新モジュールは `app.py` 末尾の import 行にも追加**し、テストが差し替える名前は `core.<名前>` で参照する
  - Web フロント: `templates/index.html`（マークアップ）＋ `static/js/index/*.js`（古典スクリプト・グローバル共有・読み込み順が依存順）→ `static/js/index/README.md`。CSS は `static/css/*.css`（並び順 = カスケード順、`templates/partials/_styles.html`）→ `static/css/README.md`
  - Android: `ui/workspace/WorkspaceController.kt`（状態）＋ `Workspace*.kt`（拡張関数）→ `ui/workspace/README.md`

## 変更時の手順
アプリケーションのソースコードを変更した場合、以下の手順を必ず行ってください。
1. `引き継ぎ資料.txt` を必要に応じて更新する
2. `python3 scripts/check_code_size.py` を実行し、`FAIL` があれば「コード肥大の管理」に従って分割・README 更新を行う
3. `git status` で差分を確認し、`git pull --rebase origin main` の後に `git add` / `git commit` / `git push` を行う
4. Web版（`app/*.py`・`templates/`・`static/` 等）を変更した場合は `sudo systemctl restart stt-gemini` でサービスを再起動する
5. Android版（`app/android/`・`.github/workflows/`）を変更した場合は、push 後に GitHub Actions のビルドが成功したことを確認する（サービス再起動は不要）
