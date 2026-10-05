# `app/` — バックエンド

Flask アプリケーション本体です。共有オブジェクトとフックは `app.py`（ハブ）に置き、ルートとバックグラウンド処理は用途別のモジュールに分けています（[モジュール構成](#モジュール構成)）。

関連ドキュメント:

- プロンプト全文 → [../docs/PROMPTS.md](../docs/PROMPTS.md)
- テンプレート → [templates/README.md](templates/README.md)
- 静的ファイル → [static/README.md](static/README.md)
- ルート概要 → [../README.md](../README.md)

---

## 構成

| パス | 役割 |
|------|------|
| `app.py` | ハブ: 設定・拡張・DB モデル・Redis タスク管理・セキュリティフック・共通ヘルパー・クリーンアップ。末尾で下記モジュールを import |
| `routes_*.py` / `streaming.py` / `processors*.py` / `prompts.py` | ルートとバックグラウンド処理（→ 下記） |
| `requirements.txt` | Python 依存（ピン留め） |
| `.env` | 秘密情報（**git 管理外**） |
| `templates/` | Jinja2 HTML |
| `static/` | CSS 等 |
| `uploads/` | ユーザー音声の一時保存（**git 管理外**） |
| `uploads/_chunks/` | 並列アップロード用チャンク（1 時間で削除） |

---

## モジュール構成

`app.py` を 1 ファイルに保つとエージェントの読み込みコストが大きいため、用途別に分割しています。
エンドポイント名・URL・挙動は分割前と同一です（`url_map` を分割前後で比較して一致を確認済み）。

| ファイル | 内容 | 主な名前 |
|----------|------|----------|
| `app.py` | ハブ（約 750 行）。設定、`db` / `login_manager` / `sock` / `fernet` / `redis_client`、許可モデル定数、タスク管理、レート制限、CSRF・`before_request` / `after_request`、DB モデル、単語・履歴コンテキスト、`cleanup_old_data`、`static_v()` | `create_task` `update_task` `get_task` `check_security` `User` `History` |
| `routes_auth.py` | 画面・認証・設定・API キー・アカウント削除 | `/`, `/welcome`, `/login`, `/register`, `/settings`, `/api/save_api_key` |
| `routes_words.py` | 単語セット / 単語 / 読み仮名生成 / インポート・エクスポート | `/api/word_sets/*`, `/api/words/*`, `/api/yomigana/generate` |
| `routes_transcribe.py` | 文字起こし系ルート | `/transcribe`, `/transcribe_live_finalize`, `/reanalyze`, `/improve`, `/correct_rephrase` |
| `routes_grok_live.py` | Grok Live（WebSocket） | `ws_grok_live` |
| `routes_files.py` | ファイル・履歴 API、チャンクアップロード | `/api/upload_chunk`, `/api/upload_complete`, `/api/files`, `/api/history` |
| `routes_tasks.py` | タスク一覧・キャンセル・SSE 再接続 | `/api/tasks`, `/api/task_stream/<id>` |
| `batch.py` | Gemini Batch API クライアント（Files upload / `batchGenerateContent` / 状態取得 / 結果パース）と `BatchJob` モデル。公式 Batch があるのは Gemini 通常7モデルのみ（`BATCH_MODELS`）。テーブルは import 時に `checkfirst` で作成 | `BatchJob` `create_batch` `refresh_job` `import_job` |
| `routes_batch.py` | Batch ジョブの投入・一覧・取り込み・取消・削除と `/batch` ページ。完了検知はブラウザ表示中のポーリング（サーバー常駐ワーカー無し） | `/api/batches`, `/api/batches/<id>/import`, `/batch` |
| `streaming.py` | Gemini 汎用バックグラウンド処理と SSE ジェネレータ | `process_gemini_background` `stream_task_updates` `create_stream_response` |
| `processors.py` | Gemini 文字起こし / Gemini Live / Grok STT のバックグラウンド処理 | `process_gemini_transcribe_background` `process_grok_stt_background` |
| `processors_openai.py` | OpenAI（GPT Transcribe / Whisper / Realtime）のバックグラウンド処理 | `process_openai_gpt_transcribe_background` |
| `prompts.py` | プロンプト定数と組み立て（全文は [../docs/PROMPTS.md](../docs/PROMPTS.md)） | `VERBATIM_INSTRUCTION` `build_transcription_prompt` `build_reanalyze_prompt` `build_improve_prompt`（通常経路と Batch で共有） |

### 依存の向きと規約（重要）

```text
app.py（ハブ）  ←  routes_* / streaming / processors*   （各モジュールが ハブ を import）
                    routes_*  →  streaming / processors* / prompts   （一方向。循環させない）
```

- ハブは **定義をすべて終えた最後**に分割モジュールを `import` する。ルートは `@app.route` で登録されるため、import されないとエンドポイントが消える。**新しいモジュールを足したら `app.py` 末尾の import 行にも足す**
- 各モジュールの先頭は `import app as core` と `from app import <名前>`。不変のオブジェクト（`app` `db` `logger` `User` 定数など）は `from app import ...` で取る
- **テストが差し替える可変名は `core.<名前>` で参照する**: `core.redis_client` `core.create_task` `core.update_task` `core.get_task` `core.save_history` `core.verify_turnstile` `core.MAX_CHUNK_BYTES` `core.MAX_XAI_AUDIO_BYTES`。`from app import redis_client` と書くと、`patch.object(application, ...)` や `application.redis_client = FakeRedis()` が効かなくなる
- 直接起動（`python app.py`）でも `import app` が同じモジュールを指すよう、`app.py` 冒頭で `sys.modules['app']` を張っている。gunicorn の `app:app` はそのまま動く
- 関数の置き場所を動かしたら、`tests/test_security.py` の `application.<名前>` / `streaming.<名前>` も見直す
- 構文・未定義名の確認: `pip install pyflakes` のうえ `python -m pyflakes app/*.py`（`undefined name` が出ないこと）

---

## 依存関係

```text
Flask==3.1.3
Werkzeug==3.1.6
Flask-Login==0.6.3
Flask-SQLAlchemy==3.1.1
SQLAlchemy==2.0.23
PyMySQL==1.2.0
cryptography==49.0.0
gunicorn==26.0.0
python-dotenv==1.2.2
redis==8.0.1
requests==2.34.2
```

インストール:

```bash
cd app
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

---

## 環境変数（`.env`）

| 変数 | 説明 |
|------|------|
| `SECRET_KEY` | セッション署名 |
| `SQLALCHEMY_DATABASE_URI` | 例: `mysql+pymysql://user:pass@127.0.0.1:3306/stt_gemini_db` |
| `ENCRYPTION_KEY` | Fernet キー。ユーザーの Gemini / xAI API キーを暗号化 |

Fernet キー生成:

```bash
python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
```

---

## 起動

```bash
# 開発
python app.py
# 既定ポート: 8003

# 本番想定
gunicorn -w 2 -b 127.0.0.1:8003 --timeout 300 app:app
```

外部公開時は Apache 等で HTTPS 終端し、`ProxyFix` 済みの Flask へプロキシすることを想定しています。

---

## データモデル（MariaDB）

### `user`

| カラム | 説明 |
|--------|------|
| `id` | PK |
| `username` | 一意 |
| `password` | ハッシュ |
| `encrypted_api_key` | Gemini API キー（Fernet） |
| `encrypted_xai_api_key` | xAI API キー（Fernet） |
| `retention_minutes` | 履歴・音声の保持分 |
| `is_locked` | アカウントロック |

起動時に `encrypted_xai_api_key` が無ければ `ALTER TABLE` で追加を試みます。

### `history`

| カラム | 説明 |
|--------|------|
| `action_type` | `transcribe` / `improve` / `reanalyze` 等 |
| `input_summary` | 入力・指示の要約 |
| `thought_text` | モデル思考 |
| `result_text` | 最終テキスト |
| `timestamp` | UTC |

### `word_set` / `word`

セット単位で有効化し、読み（`reading`）→ 置換（`replacement`）をプロンプトまたはサーバー置換に使います。

---

## Redis

接続: `127.0.0.1:6379`（現状パスワードなし。本番では `requirepass` 推奨）

| キー | 用途 |
|------|------|
| `task:{uuid}` | タスク状態 Hash（`running` / `done` / `error` / `cancelled`） |
| `user:{id}:tasks` | ユーザーのタスク ID 集合 |
| `user:{id}:active_task` | 同時実行 1 本制限 |
| レート制限キー | ユーザー×モデルの回数制限 |

タスク TTL: 24h。アクティブタスク TTL: 約 20 分。

---

## 許可モデル ID

```python
ALLOWED_MODELS = {
    'gemini-3.5-flash',
    'gemini-3-flash-preview',
    'gemini-3.1-flash-lite',
    'grok-stt',
}
```

不正値は `gemini-3.5-flash` にフォールバック。

---

## 主要 HTTP ルート

### 画面

| メソッド | パス | 説明 |
|----------|------|------|
| GET | `/` | メイン（要ログイン） |
| GET | `/welcome` | 未ログイン向け |
| GET/POST | `/login`, `/register` | 認証 |
| POST | `/logout` | ログアウト |
| GET/POST | `/settings` | API キー・保持時間・テーマ等 |
| GET/POST | `/request_unlock` | ロック解除申請 |

### 文字起こし・改善（SSE）

| メソッド | パス | 説明 |
|----------|------|------|
| POST | `/transcribe` | 音声アップロード＋文字起こし |
| POST | `/reanalyze` | 最終音声の再分析 |
| POST | `/improve` | テキスト改善 |
| POST | `/api/upload_chunk` | 大容量チャンク受信 |
| POST | `/api/upload_complete` | チャンク結合＋文字起こし |

レスポンスは SSE。ヘッダ `X-Task-ID` でタスク ID を返します。

### タスク復帰

| メソッド | パス | 説明 |
|----------|------|------|
| GET | `/api/tasks` | ユーザーのタスク一覧 |
| GET | `/api/task_stream/<task_id>` | SSE 再接続 |
| POST | `/api/tasks/<task_id>/cancel` | キャンセル |

### 履歴・ファイル・単語

| メソッド | パス | 説明 |
|----------|------|------|
| GET | `/api/history` | 履歴 |
| POST | `/api/delete_history/<id>` | 履歴 1 件削除 |
| POST | `/api/clear_history` | 履歴クリア |
| POST | `/api/clear_all` | 履歴＋音声クリア |
| GET | `/api/files` | 保存音声一覧 |
| GET | `/uploads/<filename>` | 音声配信（所有者のみ） |
| POST | `/api/delete_file/<filename>` | 音声削除 |
| POST | `/delete_audio` | セッション上の最終音声削除 |
| * | `/api/word_sets/*`, `/api/words/*` | 単語セット CRUD |

### API キー（リロード不要）

| メソッド | パス | 説明 |
|----------|------|------|
| GET | `/api/check_api_keys` | 両キー設定有無 |
| POST | `/api/check_api_key` | 指定モデルのキー有無 |
| POST | `/api/save_api_key` | キー保存（暗号化） |

---

## バックグラウンド処理フロー

```text
リクエスト → create_task() → Thread(
                 process_gemini_background  または
                 process_grok_stt_background
             )
クライアント ← SSE stream_task_updates(task_id) ← Redis ポーリング
完了時 → save_history()
```

- クライアント切断後もスレッドは継続（Redis 永続）
- 同時に 1 ユーザー 1 アクティブタスク（競合時 409）

---

## 音声制限（概略）

| 項目 | 値 |
|------|-----|
| 単一 POST 上限 | 100 MB（`MAX_CONTENT_LENGTH`） |
| Gemini 音声 | 100 MB |
| xAI 音声 | 500 MB |
| チャンク | 最大 6 MB × 100、並列アップロード想定 |
| 拡張子 | `.mp3` `.wav` `.m4a` `.mp4` `.webm` `.ogg` |

---

## セキュリティ関連（概要）

- セッション / Remember Cookie: Secure, HttpOnly, SameSite=Lax
- API キー: Fernet 暗号化保存
- アップロードパス: `user_{id}_` プレフィックス + `realpath` 検証
- ボット対策: Turnstile、JS チャレンジ、ハニーポット、UA ヒューリスティック
- レート制限: Redis ベース（ユーザー×モデル）

ユニットテストの対象は [../tests/README.md](../tests/README.md) を参照。

---

## クリーンアップ

デーモン糸 `cleanup_old_data` が約 60 秒周期で:

1. ユーザーごとの `retention_minutes` より古い History を削除
2. 同条件の `uploads/user_{id}_*` ファイルを削除
3. `uploads/_chunks/*` で 1 時間超のディレクトリを削除

---

## プロンプト

文字起こし・改善で使う指示文はすべて [../docs/PROMPTS.md](../docs/PROMPTS.md) にまとめています。  
定数は [prompts.py](prompts.py)（`VERBATIM_INSTRUCTION`, `REPHRASE_AWARE_INSTRUCTION`, `FILLER_REMOVAL_RULE` など）に定義しています。
