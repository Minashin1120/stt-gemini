# Gemini AI Speech-to-Text (STT)

Google Gemini 3.0 Flash Preview モデルの推論能力（Thinking Process）を活用した、高精度な音声文字起こしアプリケーションです。

## 🚀 主な機能

- **ハイブリッド音声入力**: ブラウザ上でのリアルタイム録音（MP3/WAV対応）と、音声ファイルのアップロード（ドラッグ＆ドロップ対応）の両方に対応。
- **思考プロセスの可視化**: Gemini 3.0 Flash Preview の推論プロセスをストリーミングで表示し、AIがどのように判断したかを確認可能。
- **AIによるテキスト改善**: 指示を与えることで、文字起こし結果を要約、翻訳、または口調の変更など、自在に加工。
- **カスタム単語リスト**: 専門用語や固有名詞を登録し、読みを指定することで、誤字を防ぎ文字起こし精度を向上。
- **データ保持管理**: ユーザー設定に基づき、履歴や音声ファイルを自動でクリーンアップ（5分〜1440分）。
- **多彩なデザインテーマ**: ゲーミング、レトロ、モダンなどのテーマ切り替えに対応。

## 🛠 技術スタック

- **Backend**: Python 3.11, Flask, SQLAlchemy (ORM), MariaDB, Gunicorn
- **Frontend**: HTML5, JavaScript (Vanilla ES6+), Bootstrap 5.3
- **API 通信**: Gemini REST API (Server-Sent Events によるストリーミング)
- **インフラ**: Apache 2.4 (Reverse Proxy), Systemd, SSL (Let's Encrypt)

## 📦 セットアップと実行

### 前提条件
- Python 3.11 以上
- MariaDB

### 手順
1. **リポジトリのクローン**
2. **依存関係のインストール**
   ```bash
   cd app
   python -m venv venv
   source venv/bin/activate
   pip install -r requirements.txt
   ```
3. **環境設定**
   `app/.env` ファイルを作成し、以下の項目を設定します。
   - `SECRET_KEY`: Flaskのシークレットキー
   - `SQLALCHEMY_DATABASE_URI`: MariaDBの接続URL
   - `ENCRYPTION_KEY`: APIキー暗号化用のキー
4. **実行**
   ```bash
   python app.py
   ```
   サーバーは `http://localhost:8003` で起動します。

## 📂 ディレクトリ構成
- `app/`: アプリケーションソースコード
  - `app.py`: メインロジック
  - `templates/`: HTMLテンプレート
  - `static/`: CSS, JS, 画像ファイル
  - `uploads/`: 一時的な音声ファイル保存先
- `引き継ぎ資料.txt`: 開発の詳細な引き継ぎ事項（非公開）
