# `app/static/` — 静的アセット

Flask の `static` フォルダです。CSS、メイン画面の JavaScript、録音用 AudioWorklet をリポジトリ管理しています。

親ドキュメント: [../README.md](../README.md) · テンプレート: [../templates/README.md](../templates/README.md)

---

## 構成

```text
static/
├── css/                          # 自前スタイル（→ css/README.md）
│   ├── base.css                  #   変数・アニメーション・処理中バー
│   ├── themes.css                #   テーマ別
│   ├── api-key-modal.css         #   API キーモーダル（Liquid Glass）
│   ├── shell.css                 #   アプリシェル全体
│   └── custom-select.css         #   モデル選択ドロップダウン
└── js/
    ├── pcm-capture-worklet.js    # 音声スレッド上でPCMを欠落なく収集
    └── index/                    # メイン画面のロジック 16 ファイル（→ js/index/README.md）
```

- CSS は `templates/partials/_styles.html` が並べて読み込む（**並び順 = カスケード順**）
- メイン画面の JavaScript は `templates/index.html` が `js/index/*.js` を依存順に読み込む（古典スクリプト・同一グローバルスコープ）
- どちらも URL は `static_v('...')`（`app.py` の Jinja ヘルパー）で更新時刻付きにするため、キャッシュバスターは手で上げない
- 他のテンプレート（`base.html` `welcome.html` `login.html` など）の小さなインライン JS はそのまま
- リアルタイム録音だけは、メインスレッドの負荷から分離するため AudioWorklet（`pcm-capture-worklet.js`）にしている
- Bootstrap / Bootstrap Icons は CDN から読み込む

---

## `css/`

詳細（ファイル別の内容・編集のヒント）は [css/README.md](css/README.md)。テーマは
`html[data-theme="..."]` で切り替え（`base.html` + `localStorage.app_theme`）。

| 値 | 雰囲気 |
|----|--------|
| （default / modern） | 標準的な明るい UI |
| `gaming` | RGB ウェーブ、ネオン寄り |
| `retro` | レトロ調 |
| `electronic` | ダーク電子機器風 |

テーマ名の正確な列挙は `css/themes.css` 内の `[data-theme=...]` セレクタ、および設定画面のセレクトを参照してください。

## `js/index/`

詳細は [js/index/README.md](js/index/README.md)。

---

## `js/pcm-capture-worklet.js`

`js/index/capture.js` の Web Audio グラフからモノラル float PCM を受け、4096サンプル単位で
メインスレッドへ転送します。一時停止と停止直前の端数 `flush` に対応します。
Chrome公式が非推奨としている `ScriptProcessorNode` は、AudioWorkletを利用できない
ブラウザ向けのフォールバックとしてのみ `js/index/capture.js` に残しています。

---

## 追加アセットを置く場合

| 種類 | 推奨パス | テンプレートでの参照例 |
|------|----------|------------------------|
| CSS | `static/css/` | `url_for('static', filename='css/foo.css')` |
| 画像 | `static/img/` | 同上 `img/...` |
| JS | `static/js/` | 同上 `js/...` |

大きなバイナリや生成物は git に含めないでください。
