# Gemini AI Speech-to-Text (STT)

Google Gemini（3.5 Flash / 3.0 Flash Preview / 3.1 Flash-Lite）の思考プロセス（Thinking）と、xAI Grok STT を組み合わせた高精度な音声文字起こし Web アプリです。

本番ドメイン例: `https://stt-gemini.minashin1120.com`（Apache リバースプロキシ + Gunicorn）

---

## 目次

- [主な機能](#主な機能)
- [対応モデル](#対応モデル)
- [技術スタック](#技術スタック)
- [クイックスタート](#クイックスタート)
- [ディレクトリ構成](#ディレクトリ構成)
- [ドキュメント一覧](#ドキュメント一覧)
- [環境変数](#環境変数)
- [開発・テスト](#開発テスト)
- [アーキテクチャ概要](#アーキテクチャ概要)
- [ライセンス・注意](#ライセンス注意)

---

## 主な機能

| 機能 | 説明 |
|------|------|
| **ハイブリッド音声入力** | ブラウザ録音（MP3 / 真の PCM WAV）とファイルアップロード（D&D） |
| **思考プロセス可視化** | Gemini の `thought` を SSE でストリーミング表示 |
| **テキスト改善** | 「要約して」「敬語に」など指示による再生成（任意で音声も参照） |
| **言い直し修正 / フィラー除去** | 自己修正の後続を優先、または「えーと」等を除去（Gemini のみ） |
| **カスタム単語リスト** | 読み → 置換のセットを有効/無効切替しプロンプトに注入 |
| **データ保持時間** | 履歴・音声をユーザー設定（5〜1440 分）で自動削除 |
| **大容量アップロード** | 100MB 超はチャンク並列アップロード（CDN 制限対策） |
| **バックグラウンド処理** | Redis にタスク永続化。ページ離脱後も処理継続、再訪で SSE 再接続 |
| **テーマ** | Gaming / Retro / Modern / Electronic など |
| **セキュリティ** | CSRF、JS チャレンジ、Turnstile、API キー暗号化、レート制限 |

---

## 対応モデル

| モデル ID | UI 名 | 思考プロセス | 言い直し/フィラー | 単語リスト | テキスト改善 |
|-----------|--------|:------------:|:-----------------:|:----------:|:------------:|
| `gemini-3.5-flash` | 3.5 Flash（デフォルト） | ✅ | ✅ | ✅（プロンプト） | ✅ |
| `gemini-3-flash-preview` | 3.0 Flash | ✅ | ✅ | ✅ | ✅ |
| `gemini-3.1-flash-lite` | 3.1 Flash-Lite | ✅ | ✅ | ✅ | ✅（間隔修正ボタンあり） |
| `grok-stt` | Grok STT (xAI) | ❌ | ❌ | ✅（サーバー側置換） | ❌（Gemini にフォールバック） |

- Gemini: REST `v1beta/models/{model}:streamGenerateContent`（SDK 非依存）
- Grok STT: `POST https://api.x.ai/v1/stt`（multipart）

使用しているシステムプロンプト全文は [docs/PROMPTS.md](docs/PROMPTS.md) を参照してください。

---

## 技術スタック

- **Backend**: Python 3.11, Flask 3, SQLAlchemy 2, Flask-Login, Gunicorn, Redis, cryptography (Fernet)
- **DB**: MariaDB（開発時は SQLite でもテスト可）
- **Frontend**: HTML5, Vanilla ES6+, Bootstrap 5.3, Server-Sent Events
- **Infra（本番想定）**: Apache 2.4 (Reverse Proxy / SSL), systemd, Let's Encrypt

---

## クイックスタート

### 前提

- Python 3.11+
- MariaDB（または互換 DB）
- Redis（タスク管理・レート制限）

### 手順

```bash
git clone https://github.com/Minashin1120/stt-gemini.git
cd stt-gemini/app

python -m venv venv
source venv/bin/activate   # Windows: venv\Scripts\activate
pip install -r requirements.txt
```

`app/.env` を作成:

```env
SECRET_KEY=your-flask-secret-key
SQLALCHEMY_DATABASE_URI=mysql+pymysql://USER:PASSWORD@127.0.0.1:3306/stt_gemini_db
ENCRYPTION_KEY=your-fernet-key   # python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
```

起動:

```bash
python app.py
# → http://localhost:8003
```

本番相当（Gunicorn）:

```bash
gunicorn -w 2 -b 127.0.0.1:8003 --timeout 300 app:app
```

詳細は [app/README.md](app/README.md) を参照。

---

## ディレクトリ構成

```text
stt-gemini/
├── README.md                 # 本ファイル（プロジェクト概要）
├── docs/
│   └── PROMPTS.md            # Gemini 向けプロンプト全文・組み立て方
├── app/
│   ├── README.md             # バックエンド・API・DB
│   ├── app.py                # メインロジック（単一モジュール）
│   ├── requirements.txt
│   ├── templates/            # Jinja2 テンプレート（→ templates/README.md）
│   ├── static/               # CSS など（→ static/README.md）
│   └── uploads/              # 音声一時保存（git 管理外）
└── tests/
    ├── README.md             # テストの実行方法
    └── test_security.py      # セキュリティ・アップロード系ユニットテスト
```

---

## ドキュメント一覧

| パス | 内容 |
|------|------|
| [README.md](README.md) | 概要・セットアップ・機能一覧 |
| [docs/PROMPTS.md](docs/PROMPTS.md) | **使用プロンプトの定義・動的コンテキスト・間隔修正指示** |
| [app/README.md](app/README.md) | 環境変数、モデル、ルート API、DB スキーマ、Redis タスク |
| [app/templates/README.md](app/templates/README.md) | 画面テンプレートと主要 JS 責務 |
| [app/static/README.md](app/static/README.md) | テーマ CSS |
| [tests/README.md](tests/README.md) | テスト実行とカバレッジの意図 |

---

## 環境変数

| 変数 | 必須 | 説明 |
|------|:----:|------|
| `SECRET_KEY` | ✅ | Flask セッション署名 |
| `SQLALCHEMY_DATABASE_URI` | ✅ | DB 接続 URL |
| `ENCRYPTION_KEY` | ✅ | ユーザー API キー暗号化用 Fernet キー（URL-safe base64） |

ユーザーごとの **Gemini API キー / xAI API キー** は設定画面から入力し、DB に暗号化保存します（`.env` には置きません）。

---

## 開発・テスト

```bash
cd stt-gemini
source app/venv/bin/activate   # またはプロジェクトの venv
python -m unittest discover -s tests -v
```

詳細は [tests/README.md](tests/README.md)。

---

## アーキテクチャ概要

```text
Browser (録音 / アップロード / SSE)
    │  HTTPS
    ▼
Apache (optional)  →  Gunicorn / Flask (app.py :8003)
    │                      │
    │                      ├── MariaDB (User, History, WordSet, Word)
    │                      └── Redis  (task:{id}, レート制限, アクティブタスク)
    │
    ├── Gemini REST API (streamGenerateContent + thinkingConfig)
    └── xAI STT API     (POST /v1/stt)
```

1. `/transcribe` 等でタスク ID を発行し、ワーカースレッドで API 呼び出し
2. 進捗・結果は Redis に書き込み
3. クライアントは SSE（`stream_task_updates`）で Redis をポーリング受信
4. 完了後に History へ保存。保持時間経過でバックグラウンド削除

---

## ライセンス・注意

- 本リポジトリの利用にあたっては、Google Gemini / xAI の各 API 利用規約と料金に従ってください。
- API キー・DB パスワード・`ENCRYPTION_KEY` はコミットしないでください（`.gitignore` で `.env` を除外済み）。
- 本番では Redis の `requirepass`、HTTPS、セッション Cookie の Secure 設定を有効にしてください。
