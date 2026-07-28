# `app/static/` — 静的アセット

Flask の `static` フォルダです。CSS と録音用 AudioWorklet を管理しています。

親ドキュメント: [../README.md](../README.md) · テンプレート: [../templates/README.md](../templates/README.md)

---

## 構成

```text
static/
├── css/
│   └── style.css                  # テーマ別スタイル・アニメーション・モーダル
└── js/
    └── pcm-capture-worklet.js     # 音声レンダリングスレッドでPCMを安定回収
```

JavaScript の大半は `templates/index.html` 等にインライン配置し、AudioWorklet だけは別ファイルです。
Bootstrap / Bootstrap Icons は CDN から読み込みます。

---

## `css/style.css`

### テーマ

`html[data-theme="..."]` で切り替え（`base.html` + `localStorage.app_theme`）。

想定テーマ例:

| 値 | 雰囲気 |
|----|--------|
| （default / modern） | 標準的な明るい UI |
| `gaming` | RGB ウェーブ、ネオン寄り |
| `retro` | レトロ調 |
| `electronic` | ダーク電子機器風 |

テーマ名の正確な列挙は `style.css` 内の `[data-theme=...]` セレクタ、および設定画面のセレクトを参照してください。

### 主な UI ブロック

- カード・タブ・録音ビジュアライザ周りのレイアウト
- 処理中バー（`.processing-bar`）
- API キーモーダル（`.api-key-modal`）: Liquid Glass 風の疑似屈折、色収差インセット、マウス追従グロー（`--ak-glow-x` / `--ak-glow-y`）
- ダークテーマ向けの同モーダル色調整

### 編集のヒント

1. テーマ差分は可能な限り `data-theme` セレクタに閉じる
2. モーダルの `z-index` / backdrop は Bootstrap モーダルと競合しやすいので、変更時はログイン・削除確認・API キーの重なりを確認
3. アニメーションを増やす場合は `prefers-reduced-motion` への配慮を検討

---

## 追加アセットを置く場合

| 種類 | 推奨パス | テンプレートでの参照例 |
|------|----------|------------------------|
| CSS | `static/css/` | `url_for('static', filename='css/foo.css')` |
| 画像 | `static/img/` | 同上 `img/...` |
| JS | `static/js/` | 同上 |

大きなバイナリや生成物は git に含めないでください。
