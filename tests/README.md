# `tests/` — テスト

セキュリティ・アップロード経路・セッション周りの回帰を防ぐユニットテストです。

親ドキュメント: [../README.md](../README.md) · アプリ: [../app/README.md](../app/README.md)

---

## 実行方法

リポジトリルートから:

```bash
# 仮想環境を使う場合
source app/venv/bin/activate
pip install -r app/requirements.txt   # 未導入なら

python -m unittest discover -s tests -v
```

特定ファイルのみ:

```bash
python -m unittest tests.test_security -v
```

---

## 含まれるテスト

| ファイル | 内容 |
|----------|------|
| `test_security.py` | アップロード検証、パス traversal 防止、モバイル録音経路、セッション/認証まわり、Redis をモックしたタスク周りなど |

### テスト環境の仕掛け

- 一時ディレクトリ上の SQLite（`SQLALCHEMY_DATABASE_URI`）
- 実行時に `SECRET_KEY` / `ENCRYPTION_KEY` を生成
- `threading.Thread.start` を no-op にしてバックグラウンド Gemini 呼び出しを起動しない
- `FakeRedis` で Redis をインメモリ模擬

本番の MariaDB / 実 Redis / 実 API キーは **不要** です。

---

## カバレッジの意図（現状）

重点:

- 不正な拡張子・巨大ペイロード・他ユーザーファイルへのアクセス拒否
- マイク取得時の必須OFF制約、モバイル内蔵マイクの厳密指定、AudioWorklet配信
- ログイン必須エンドポイント
- タスク同時実行制限やキャンセルの基本挙動（モック前提）

あえて薄い／未カバーになりやすい箇所:

- 実際の Gemini / xAI ストリーミング応答のパース
- フロントエンド（`index.html` の録音・SSE 再接続）
- Apache / Gunicorn 配置

E2E やプロンプト品質は手動確認（[docs/PROMPTS.md](../docs/PROMPTS.md) の変更指針参照）を想定しています。

---

## テスト追加の指針

1. `app` を import する前に環境変数と Thread モックを済ませる（既存 `test_security.py` 冒頭のパターンを踏襲）
2. ファイル I/O は `tempfile` + `UPLOAD_FOLDER` 差し替えで完結させる
3. 外部 HTTP（Gemini / xAI / Turnstile）は `unittest.mock.patch` で遮断する
4. アサーションはステータスコードと JSON の `error` キーを中心に、実装詳細の文字列全文一致は避ける

---

## CI 向けワンライナー例

```bash
python -m unittest discover -s tests -q
```

exit code `0` で成功です。
