# `static/css/` — 自前スタイル

もとは 1 ファイル（約 1,200 行）だった `style.css` を、責務ごとに 5 ファイルへ分けたものです。
**読み込み順がカスケード順**になるため、`templates/partials/_styles.html` の並びを変えないでください
（`base.html` と `welcome.html` がこの部分テンプレートを `include` します）。

親ドキュメント: [../README.md](../README.md)

| # | ファイル | 内容 |
|---|----------|------|
| 1 | `base.css` | `:root` 変数、アニメーション定義（RGB ボーダー等）、処理中プログレスバー |
| 2 | `themes.css` | テーマ別（`html[data-theme=...]`）: gaming / retro（昭和レトロ）/ electronic / stylish / business / material3 / material2 / classic と共通の上書き |
| 3 | `api-key-modal.css` | API キー入力モーダル（Liquid Glass、ダーク時の上書き、モデル選択肢、破棄ビュー） |
| 4 | `shell.css` | アプリシェル全体のレイアウト（ナビ・カード・録音 UI など）、認証・設定・ランディング、レスポンシブ、`prefers-reduced-motion` |
| 5 | `custom-select.css` | カスタムセレクト（モデル選択ドロップダウン） |

## 編集のヒント

1. テーマ差分は `themes.css` の `data-theme` セレクタに閉じる。共通の見た目は `shell.css` へ
2. モーダルの `z-index` / backdrop は Bootstrap モーダルと競合しやすい。ログイン・削除確認・API キーの重なりを確認する
3. アニメーションを足すときは `prefers-reduced-motion` への配慮を検討する
4. `shell.css` が約 480 行で最大。500 行を超えたら「認証・設定・ランディング」などの単位で切り出し、
   `partials/_styles.html` の並び（カスケード順）とこの表を更新する
5. ファイル名を変えた / 増やした場合は、Android 版の `ui/theme/Themes.kt` など対応箇所の見直しも AGENTS.md の対応表で確認する
