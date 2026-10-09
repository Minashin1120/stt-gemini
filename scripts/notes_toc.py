#!/usr/bin/env python3
"""ローカルノート（非公開・git管理外）の目次（行番号つき）を更新する / 表示する。

  python3 scripts/notes_toc.py          # 目次ブロックだけを表示（読むのはこれだけ）
  python3 scripts/notes_toc.py --update # `## ` 見出しから行範囲を再計算して目次を書き換える
  python3 scripts/notes_toc.py --check  # 目次が最新か確認（古ければ終了コード1）

目次の各行は `開始-終了 | 見出し | 要約`。見出しが同じ行の要約は更新時に引き継がれる。
新しい見出しは `(要約未記入)` で追加されるので、手で短い要約に書き換える。
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def notes_path():
    """ノートのパスは追跡対象外の `.git/notes_path`（1行・ROOT相対）から読む。"""
    cfg = os.path.join(ROOT, '.git', 'notes_path')
    try:
        with open(cfg, encoding='utf-8') as f:
            return os.path.join(ROOT, f.read().strip())
    except OSError:
        raise SystemExit('.git/notes_path が無い（ノートのファイル名を1行で書く）')


PATH = None
START = '=== 目次 ==='
END = '=== 目次ここまで ==='
TODO = '(要約未記入)'


def load():
    with open(notes_path(), encoding='utf-8') as f:
        return f.read().split('\n')


def find_block(lines):
    s = next((i for i, l in enumerate(lines) if l.strip() == START), None)
    e = next((i for i, l in enumerate(lines) if l.strip() == END), None)
    return (s, e) if s is not None and e is not None and s < e else (None, None)


def old_summaries(lines, s, e):
    out = {}
    for l in lines[s + 1:e]:
        parts = [p.strip() for p in l.split('|', 2)]
        if len(parts) == 3 and parts[0][:1].isdigit():
            out[parts[1]] = parts[2]
    return out


def build_rows(lines, s, e, summaries):
    heads = [i for i, l in enumerate(lines) if l.startswith('## ') and not (s < i < e)]
    rows = []
    for n, i in enumerate(heads):
        nxt = heads[n + 1] if n + 1 < len(heads) else len(lines)
        last = nxt - 1
        while last > i and lines[last].strip() in ('', '---'):
            last -= 1
        title = lines[i][3:].strip()
        rows.append((i + 1, last + 1, title, summaries.get(title, TODO)))
    return rows


def render(lines):
    s, e = find_block(lines)
    if s is None:
        raise SystemExit('目次ブロック（%s 〜 %s）が見つからない' % (START, END))
    summaries = old_summaries(lines, s, e)
    rows = build_rows(lines, s, e, summaries)
    # 目次自身の行数が変わると行番号がずれるため、安定するまで繰り返す
    for _ in range(5):
        block = [START] + ['%d-%d | %s | %s' % r for r in rows] + [END]
        new = lines[:s] + block + lines[e + 1:]
        s2, e2 = find_block(new)
        rows2 = build_rows(new, s2, e2, summaries)
        if rows2 == rows:
            return new
        rows = rows2
    return new


def update():
    lines = load()
    new = render(lines)
    if new != lines:
        with open(notes_path(), 'w', encoding='utf-8') as f:
            f.write('\n'.join(new))
    return new != lines


def show():
    lines = load()
    s, e = find_block(lines)
    if s is None:
        raise SystemExit('目次ブロックが見つからない')
    print('\n'.join(lines[s:e + 1]))


if __name__ == '__main__':
    if '--update' in sys.argv:
        print('updated' if update() else 'up-to-date')
    elif '--check' in sys.argv:
        cur = load()
        ok = render(cur) == cur
        print('OK' if ok else 'STALE: python3 scripts/notes_toc.py --update')
        sys.exit(0 if ok else 1)
    else:
        show()
