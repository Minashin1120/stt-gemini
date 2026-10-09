#!/usr/bin/env python3
"""更新後作業（AGENTS.md「変更時の手順」の 2〜5）を 1 回の呼び出しで実行する。

    python3 scripts/post_update.py -m "コミットメッセージ"      # 通常実行
    python3 scripts/post_update.py --dry-run                    # 読み取りのみ（何をするかを表示）
    python3 scripts/post_update.py -m "..." --add PATH [PATH..] # 新規ファイルも追跡に加える
    python3 scripts/post_update.py -m "..." --resume            # 【停止中】節がある状態から再開
    python3 scripts/post_update.py --no-push -m "..."           # コミットまでで止める

実行する内容: コード肥大チェック → git status 点検 → add -u/commit → pull --rebase → push
→ Web版変更なら再起動と稼働確認 → Android版変更なら CI とリリースの成功確認（待機込み）。
成功時は 1〜数行、失敗時は失敗した手順と要点のみを出力し、ローカルノート に【停止中】節を書いて
終了コード 1（待機のタイムアウトは 3）で止まる。自力での修復や破壊的操作（reset --hard / force push）はしない。
成功するとローカルノート の【保留中】【停止中】節を削除する。

CI 待ちは最大 25 分かかるため、Bash の上限（10 分）を超えうる。その場合は run_in_background で実行する。
詳細は scripts/README.md を参照。
"""
import argparse
import fnmatch
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = subprocess.check_output(['git', 'rev-parse', '--show-toplevel'], text=True).strip()
NOTES = os.path.join(ROOT, 'ローカルノート')
REPO = 'Minashin1120/stt-gemini'
SERVICE = 'stt-gemini'
PENDING_H = '## 【保留中】更新後作業'
STOP_H = '## 【停止中】更新後作業の失敗'
# コミットしてはいけないもの（AGENTS.md「Git管理」）。status に出たら停止する。
FORBIDDEN = ['app/venv/*', 'app/.env*', 'app/*.log', 'app/uploads/*', '.codex/*', '.gemini/*',
             'cookies.txt', 'test.mp3', 'ローカルノート', 'app/android/.gradle/*',
             'app/android/*/build/*', 'app/android/build/*', '*local.properties']
POLL_SEC = 20

state = {'committed': False, 'pushed': False, 'restarted': False}


def sh(cmd, check=False):
    r = subprocess.run(cmd, cwd=ROOT, text=True, capture_output=True)
    if check and r.returncode:
        raise Fail(' '.join(cmd), (r.stdout + r.stderr))
    return r


class Fail(Exception):
    def __init__(self, step, detail, code=1):
        super().__init__(step)
        self.step, self.detail, self.code = step, detail, code


def tail(text, n=8):
    lines = [l for l in text.strip().splitlines() if l.strip()]
    return '\n'.join(lines[-n:])


# ---- ローカルノート の節操作 -------------------------------------------------
def read_notes():
    try:
        with open(NOTES, encoding='utf-8') as f:
            return f.read().split('\n')
    except FileNotFoundError:
        return []


def has_section(lines, header):
    return any(l.startswith(header) for l in lines)


def drop_section(lines, header):
    out, skip = [], False
    for l in lines:
        if l.startswith('## ') or l.startswith('# '):
            skip = l.startswith(header)
        if not skip:
            out.append(l)
    return out


def write_stop_section(step, detail):
    lines = drop_section(read_notes(), STOP_H)
    st = sh(['git', 'status', '-sb']).stdout.splitlines()[:1]
    head = sh(['git', 'log', '-1', '--oneline']).stdout.strip()
    body = [STOP_H, f'- 失敗した手順: {step}（{time.strftime("%Y-%m-%d %H:%M")}）',
            '- エラー要点:'] + ['    ' + l for l in tail(detail).splitlines()] + [
            f'- 現在の状態: commit済み={state["committed"]} push済み={state["pushed"]} '
            f'再起動済み={state["restarted"]} / HEAD: {head} / {st[0] if st else ""}',
            '- 再開方法: 原因を直したら `python3 scripts/post_update.py --resume -m "<メッセージ>"` '
            '（commit済みで変更が無ければ -m は不要）。直ったら本節は成功時に自動削除される。',
            '- 注意: 推測を含む原因は未確認。まずエラー要点と該当ログを確認すること。', '']
    idx = next((i for i, l in enumerate(lines) if l.startswith(PENDING_H)), None)
    if idx is None:
        idx = next((i for i, l in enumerate(lines) if l.strip() == '---'), -1) + 1
    lines[idx:idx] = body
    with open(NOTES, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines))


def clear_sections():
    lines = read_notes()
    if not lines:
        return
    new = drop_section(drop_section(lines, PENDING_H), STOP_H)
    if new != lines:
        with open(NOTES, 'w', encoding='utf-8') as f:
            f.write('\n'.join(new))


# ---- GitHub API ---------------------------------------------------------------
def api(path):
    headers = {'Accept': 'application/vnd.github+json'}
    tok = os.path.expanduser('~/.github_pat')
    if os.path.exists(tok):
        headers['Authorization'] = 'Bearer ' + open(tok).read().strip()
    req = urllib.request.Request('https://api.github.com/repos/' + REPO + path, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        raise Fail('GitHub API ' + path, f'HTTP {e.code}（レート制限の可能性。~/.github_pat を確認）')
    except Exception as e:  # ネットワーク一時エラー
        raise Fail('GitHub API ' + path, str(e))


def wait_run(workflow, match, deadline, label, appear_sec=240):
    """match(run)->bool を満たす run を探し、完了まで待つ。成功なら run を返す。"""
    start = time.time()
    while time.time() < deadline:
        runs = api(f'/actions/workflows/{workflow}/runs?per_page=10')['workflow_runs']
        run = next((r for r in runs if match(r)), None)
        if run and run['status'] == 'completed':
            if run['conclusion'] != 'success':
                raise Fail(label, f'{run["conclusion"]}: {run["html_url"]}')
            return run
        if not run and time.time() - start > appear_sec:
            raise Fail(label, f'{workflow} の実行が {appear_sec} 秒以内に現れない')
        time.sleep(POLL_SEC)
    raise Fail(label, '待機がタイムアウト（実行中の可能性。Actions画面で確認）', code=3)


# ---- 本体 ---------------------------------------------------------------------
def classify(files):
    android = any(f.startswith('app/android/') or f == '.github/workflows/android.yml' for f in files)
    web = any((f.startswith('app/') and not f.startswith('app/android/')) for f in files)
    return web, android


def run(args):
    lines = read_notes()
    if has_section(lines, STOP_H) and not args.resume:
        raise Fail('開始前確認', 'ローカルノート に【停止中】節がある。直してから --resume で再実行する', code=2)
    deadline = time.time() + args.timeout * 60

    # 2. コード肥大チェック
    r = sh([sys.executable, 'scripts/check_code_size.py'])
    out = r.stdout.strip()
    if r.returncode:
        raise Fail('2 コード肥大チェック(FAIL)', out)

    # 3. git status 点検
    sh(['git', 'fetch', 'origin', 'main'], check=True)
    status = sh(['git', 'status', '--short']).stdout.splitlines()
    paths = [l[3:].strip('"') for l in status]
    bad = [p for p in paths if any(fnmatch.fnmatch(p, pat) for pat in FORBIDDEN)]
    if bad:
        raise Fail('3 git status 点検', '追跡してはいけないファイルが混入: ' + ', '.join(bad))
    untracked = [l[3:] for l in status if l.startswith('??') and l[3:] not in (args.add or [])]
    if args.dry_run:
        print('dry-run: size=' + out.splitlines()[0][:60])
        print('dry-run: 変更', len(status), '件 / 未追跡(add -u では対象外):', untracked or 'なし')
        return 'dry-run 完了（何も変更していない）'

    # add / commit
    sh(['git', 'add', '-u'], check=True)
    if args.add:
        sh(['git', 'add', '--'] + args.add, check=True)
    if sh(['git', 'diff', '--cached', '--quiet']).returncode:
        if not args.message:
            raise Fail('3 commit', '-m が無い（コミット対象の変更あり）')
        sh(['git', 'commit', '-m', args.message], check=True)
        state['committed'] = True

    r = sh(['git', 'pull', '--rebase', 'origin', 'main'])
    if r.returncode:
        raise Fail('3 pull --rebase', r.stdout + r.stderr)
    files = sh(['git', 'diff', '--name-only', 'origin/main...HEAD'], check=True).stdout.split('\n')
    files = [f for f in files if f]
    if not files and not state['committed']:
        clear_sections()
        return '変更なし。更新後作業は不要'
    web, android = classify(files)
    if args.no_push:
        return f'commit まで完了（push しない）。web={web} android={android}'

    t_push = time.time() - 60
    r = sh(['git', 'push', 'origin', 'HEAD:main'])
    if r.returncode:
        raise Fail('3 push', r.stdout + r.stderr)
    state['pushed'] = True
    sha = sh(['git', 'rev-parse', 'HEAD'], check=True).stdout.strip()
    done = [f'push済み {sha[:7]}']

    # 4. Web 再起動
    if web:
        r = sh(['sudo', '-n', 'systemctl', 'restart', SERVICE])
        if r.returncode:
            raise Fail('4 systemctl restart', r.stdout + r.stderr)
        state['restarted'] = True
        time.sleep(3)
        if sh(['systemctl', 'is-active', SERVICE]).stdout.strip() != 'active':
            raise Fail('4 再起動後の稼働確認', sh(['journalctl', '-u', SERVICE, '-n', '8', '--no-pager']).stdout)
        done.append('Web再起動OK')

    # 5/6. Android CI とリリース
    if android and not args.skip_ci:
        wait_run('android.yml', lambda x: x['head_sha'] == sha, deadline, '5 Android CI')
        done.append('CI成功')
        from datetime import datetime, timezone
        iso = lambda s: datetime.strptime(s, '%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=timezone.utc).timestamp()
        wait_run('release.yml', lambda x: iso(x['created_at']) >= t_push, deadline, '6 release.yml', appear_sec=420)
        rel = api('/releases?per_page=1')[0]
        if 'app-release.apk' not in [a['name'] for a in rel['assets']]:
            raise Fail('6 Release確認', f'{rel["tag_name"]} に app-release.apk が無い')
        done.append(f'Release {rel["tag_name"]} OK')
    clear_sections()
    return ' / '.join(done)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('-m', '--message')
    ap.add_argument('--add', nargs='*', help='新規ファイルも追跡に加える')
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('--no-push', action='store_true')
    ap.add_argument('--skip-ci', action='store_true', help='CI/リリース確認を省く（通常は使わない）')
    ap.add_argument('--resume', action='store_true')
    ap.add_argument('--timeout', type=int, default=25, help='CI待ちの上限(分)')
    args = ap.parse_args()
    try:
        print('OK:', run(args))
    except Fail as e:
        print(f'STOP: {e.step}\n{tail(e.detail)}')
        if not args.dry_run and e.code != 2:
            write_stop_section(e.step, e.detail)
            print('→ ローカルノート に【停止中】節を書いた。追加作業はしないこと。')
        sys.exit(e.code)


if __name__ == '__main__':
    main()
