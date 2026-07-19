# `app/` — バックエンド

Flask アプリケーション本体です。ロジックはほぼ単一モジュール `app.py` に集約されています。

関連ドキュメント:

- プロンプト全文 → [../docs/PROMPTS.md](../docs/PROMPTS.md)
- テンプレート → [templates/README.md](templates/README.md)
- 静的ファイル → [static/README.md](static/README.md)
- ルート概要 → [../README.md](../README.md)

---

## 構成

| パス | 役割 |
|------|------|
| `app.py` | ルート、認証、Gemini/xAI 呼び出し、Redis タスク、クリーンアップ |
| `requirements.txt` | Python 依存（ピン留め） |
| `.env` | 秘密情報（**git 管理外**） |
| `templates/` | Jinja2 HTML |
| `static/` | CSS 等 |
| `uploads/` | ユーザー音声の一時保存（**git 管理外**） |
| `uploads/_chunks/` | 並列アップロード用チャンク（1 時間で削除） |

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
定数名: `VERBATIM_INSTRUCTION`, `REPHRASE_AWARE_INSTRUCTION`, `FILLER_REMOVAL_RULE`。
