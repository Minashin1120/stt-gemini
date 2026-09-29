#!/usr/bin/env python3
"""コードの肥大チェック（AIエージェントのコンテキスト肥大を防ぐ目的）。

追跡対象（および未追跡でも .gitignore 対象外）のソースファイルの行数とバイト数を測り、
しきい値を超えたファイルだけを 1 行ずつ出力する。問題が無ければ 1 行の OK だけを出す。
出力が極小なので、エージェントはこのスクリプトを実行するだけでよく、ソースを読む必要はない。

    python3 scripts/check_code_size.py          # FAIL のみ詳細表示 + WARN は件数と名前のみ
    python3 scripts/check_code_size.py --strict # WARN も FAIL 扱い（終了コード 1）

終了コード: 0 = FAIL なし / 1 = FAIL あり（分割が必要）
しきい値の意味と分割手順は AGENTS.md「コード肥大の管理」を参照。
"""
import os
import subprocess
import sys

# 1 ファイルあたりの上限。どちらか一方でも超えたら該当。
FAIL_LINES, FAIL_BYTES = 800, 40_000   # 分割必須（読み込むだけで数千〜1 万トークンを消費する）
WARN_LINES, WARN_BYTES = 500, 28_000   # 分割を検討（次の機能追加で FAIL に届く水準）

CODE_EXT = {'.py', '.kt', '.kts', '.js', '.html', '.css', '.sh', '.yml', '.yaml', '.gradle'}
# 生成物・第三者コードなど、分割しても意味がないもの
SKIP_PARTS = ('/build/', '/.gradle/', '/node_modules/', '/venv/', '/res/font/')
SKIP_SUFFIX = ('.min.js', '.min.css')


def repo_root():
    return subprocess.check_output(['git', 'rev-parse', '--show-toplevel'], text=True).strip()


def candidate_files(root):
    out = subprocess.check_output(
        ['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=root
    ).decode('utf-8')
    for rel in filter(None, out.split('\0')):
        path = '/' + rel
        # .claude/ など個人用の隠しディレクトリは対象外（.github は対象）
        if any(part.startswith('.') and part != '.github' for part in rel.split('/')[:-1]):
            continue
        if os.path.splitext(rel)[1] not in CODE_EXT:
            continue
        if rel.endswith(SKIP_SUFFIX) or any(part in path for part in SKIP_PARTS):
            continue
        if os.path.isfile(os.path.join(root, rel)):
            yield rel


def measure(root, rel):
    with open(os.path.join(root, rel), 'rb') as handle:
        data = handle.read()
    return data.count(b'\n') + (1 if data and not data.endswith(b'\n') else 0), len(data)


def main():
    strict = '--strict' in sys.argv[1:]
    root = repo_root()
    fails, warns = [], []
    for rel in candidate_files(root):
        lines, size = measure(root, rel)
        if lines > FAIL_LINES or size > FAIL_BYTES:
            fails.append((rel, lines, size))
        elif lines > WARN_LINES or size > WARN_BYTES:
            warns.append((rel, lines, size))
    for rel, lines, size in sorted(fails, key=lambda x: -x[2]):
        print(f'FAIL {rel}: {lines} lines, {size // 1000} KB (limit {FAIL_LINES} lines / {FAIL_BYTES // 1000} KB) -> split it and update the README')
    if warns:
        top = sorted(warns, key=lambda x: -x[2])[:5]
        names = ', '.join(f'{rel} ({lines}L/{size // 1000}KB)' for rel, lines, size in top)
        more = f' +{len(warns) - len(top)} more' if len(warns) > len(top) else ''
        print(f'WARN {len(warns)} file(s) near the limit: {names}{more}')
    if not fails:
        print('OK: no file exceeds the size limit')
    return 1 if fails or (strict and warns) else 0


if __name__ == '__main__':
    sys.exit(main())
