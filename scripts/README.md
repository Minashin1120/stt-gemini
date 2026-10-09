# scripts/

開発用スクリプト。出力は意図的に極小（エージェントのトークン節約のため）。

| ファイル | 役割 |
|------|------|
| `check_code_size.py` | 1ファイルの行数/サイズのしきい値チェック（FAIL=分割必須、WARN=検討）。`post_update.py` の手順2から呼ばれる |
| `notes_toc.py` | ローカルノート（`.git/notes_path` に名前を書く） の目次（行範囲＋要約）の表示（引数なし）・更新（`--update`）・確認（`--check`）。ノートは全体を読まず、目次→必要な行範囲の順で読む（AGENTS.md「ローカルノートの読み方」） |
| `post_update.py` | 更新後作業（AGENTS.md「変更時の手順」2〜5）を1回で実行する |

## post_update.py

```
python3 scripts/post_update.py -m "メッセージ"        # 通常（メッセージ末尾に Co-Authored-By 行を含める）
python3 scripts/post_update.py --dry-run               # 読み取りのみ
python3 scripts/post_update.py -m "..." --add 新規ファイル...
python3 scripts/post_update.py --resume [-m "..."]     # 【停止中】節がある状態から再開
```

流れ: コード肥大チェック → `git status` 点検（venv・.env・生成物などの混入で停止）→ `git add -u`（+`--add`）/commit
→ `pull --rebase` → push → 変更に応じて Web再起動と稼働確認 / Android CI と release.yml と Release の apk 確認（待機込み）。

- 変更の分類は `origin/main...HEAD` のパスで判定: `app/android/**`・`android.yml` → Android、`app/` のその他 → Web。
- 新規（未追跡）ファイルは `git add -u` では入らない。AGENTS.md の方針どおり、`.gitignore` を見直した上で `--add` に渡す。
- 失敗時は止まって ローカルノート（`.git/notes_path` に名前を書く） の先頭に【停止中】節を書く。終了コード 1=失敗 / 2=【停止中】節があり未再開 / 3=待機タイムアウト。
- 成功時は【保留中】【停止中】節を削除する。破壊的操作（reset --hard / force push）はしない。
- CI待ちは最大25分（`--timeout`）。Bash の上限10分を超えうるので `run_in_background` で実行する。
- GitHub API は `~/.github_pat` があれば認証付き（値は出力しない）。
- Android版のビルドはローカルで行わない（CIに委託）。このスクリプトも Gradle を呼ばない。
