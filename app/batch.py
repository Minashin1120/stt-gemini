"""Gemini Batch API クライアントと BatchJob モデル（公式 Batch があるのは Gemini 通常モデルのみ）。"""
import json
import os
import requests
from datetime import datetime
import app as core
from app import app, db, logger

GEMINI_BASE = 'https://generativelanguage.googleapis.com'
# generateContent 系の通常モデルのみ Batch API 対象（Transcribe(Interactions)・Live・OpenAI・Grok は対象外）
BATCH_MODELS = {
    'gemini-3.6-flash',
    'gemini-3.5-flash',
    'gemini-3.5-flash-lite',
    'gemini-3-flash-preview',
    'gemini-3.1-flash-lite',
}
MAX_RUNNING_JOBS = 20
ACTIVE_STATES = ('running',)


class BatchJob(db.Model):
    __tablename__ = 'batch_job'
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey('user.id'), nullable=False, index=True)
    provider_job = db.Column(db.String(255), nullable=False)  # batches/xxxx
    model = db.Column(db.String(100), nullable=False)
    action_type = db.Column(db.String(50), nullable=False)  # transcribe / reanalyze / improve
    input_summary = db.Column(db.Text, nullable=True)
    status = db.Column(db.String(20), default='running', nullable=False)  # running / succeeded / failed / cancelled / expired
    thought_text = db.Column(db.Text, nullable=True)
    result_text = db.Column(db.Text, nullable=True)
    error = db.Column(db.Text, nullable=True)
    imported = db.Column(db.Boolean, default=False, nullable=False)
    created_at = db.Column(db.DateTime, default=datetime.utcnow)
    completed_at = db.Column(db.DateTime, nullable=True)

    def to_dict(self, with_text=False):
        d = {
            'id': self.id,
            'model': self.model,
            'action': self.action_type,
            'input': self.input_summary or '',
            'status': self.status,
            'error': self.error or '',
            'imported': bool(self.imported),
            'created': self.created_at.strftime('%Y-%m-%d %H:%M') if self.created_at else '',
            'completed': self.completed_at.strftime('%Y-%m-%d %H:%M') if self.completed_at else '',
        }
        if with_text:
            d['thought'] = self.thought_text or ''
            d['result'] = self.result_text or ''
        return d


# 本番(gunicorn)は __main__ の db.create_all() を通らないため、このテーブルだけ起動時に作る
with app.app_context():
    try:
        BatchJob.__table__.create(bind=db.engine, checkfirst=True)
    except Exception as e:
        if 'already exists' not in str(e):
            logger.error(f"batch_job table creation error: {e}")


def _headers(api_key, **extra):
    h = {'x-goog-api-key': api_key}
    h.update(extra)
    return h


def upload_file(api_key, data, mime, display_name):
    """Gemini Files API(resumable)へアップロードし、file オブジェクト(name/uri)を返す。data は bytes。"""
    start = requests.post(f'{GEMINI_BASE}/upload/v1beta/files', headers=_headers(
        api_key, **{'X-Goog-Upload-Protocol': 'resumable', 'X-Goog-Upload-Command': 'start',
                    'X-Goog-Upload-Header-Content-Length': str(len(data)),
                    'X-Goog-Upload-Header-Content-Type': mime, 'Content-Type': 'application/json'}),
        json={'file': {'display_name': display_name}}, timeout=(10, 60))
    if start.status_code not in (200, 201):
        raise RuntimeError(f'Gemini Files API Error {start.status_code}')
    upload_url = start.headers.get('X-Goog-Upload-URL')
    if not upload_url:
        raise RuntimeError('Gemini Files APIのアップロードURLを取得できませんでした')
    up = requests.post(upload_url, headers=_headers(
        api_key, **{'X-Goog-Upload-Offset': '0', 'X-Goog-Upload-Command': 'upload, finalize',
                    'Content-Length': str(len(data))}), data=data, timeout=(10, 600))
    if up.status_code not in (200, 201):
        raise RuntimeError(f'Gemini アップロードエラー {up.status_code}')
    f = up.json().get('file') or {}
    if not f.get('name') or not f.get('uri'):
        raise RuntimeError('Gemini Files APIの応答にファイル情報がありません')
    return f


def wait_file_active(api_key, file_obj, timeout_s=120):
    """音声は PROCESSING のことがあるため ACTIVE になるまで待つ。"""
    import time
    deadline = time.time() + timeout_s
    state = file_obj.get('state', 'ACTIVE')
    while state == 'PROCESSING' and time.time() < deadline:
        time.sleep(2)
        r = requests.get(f"{GEMINI_BASE}/v1beta/{file_obj['name']}", headers=_headers(api_key), timeout=(10, 30))
        if r.status_code == 200:
            state = r.json().get('state', 'ACTIVE')
    if state == 'FAILED':
        raise RuntimeError('Geminiが音声の処理に失敗しました')
    return file_obj


def create_batch(api_key, model, request_body, display_name):
    """1 リクエストの JSONL を作ってアップロードし、バッチジョブを作成して `batches/xxx` を返す。"""
    line = json.dumps({'key': 'request-1', 'request': request_body}, ensure_ascii=False)
    jsonl = upload_file(api_key, line.encode('utf-8'), 'application/jsonl', f'{display_name}.jsonl')
    r = requests.post(f'{GEMINI_BASE}/v1beta/models/{model}:batchGenerateContent',
                      headers=_headers(api_key, **{'Content-Type': 'application/json'}),
                      json={'batch': {'display_name': display_name, 'input_config': {'file_name': jsonl['name']}}},
                      timeout=(10, 60))
    if r.status_code not in (200, 201):
        raise RuntimeError(f'Gemini Batch API Error {r.status_code}')
    name = r.json().get('name')
    if not name or not name.startswith('batches/'):
        raise RuntimeError('Gemini Batch APIの応答にジョブ名がありません')
    return name


_STATE_MAP = {
    'SUCCEEDED': 'succeeded', 'FAILED': 'failed', 'CANCELLED': 'cancelled', 'EXPIRED': 'expired',
    'PENDING': 'running', 'RUNNING': 'running',
}


def _normalize_state(raw):
    s = str(raw or '').upper()
    for prefix in ('BATCH_STATE_', 'JOB_STATE_'):
        if s.startswith(prefix):
            s = s[len(prefix):]
    return _STATE_MAP.get(s, 'running')


def fetch_state(api_key, name):
    """(status, responses_file, error) を返す。通信失敗時は例外。"""
    r = requests.get(f'{GEMINI_BASE}/v1beta/{name}', headers=_headers(api_key), timeout=(10, 30))
    if r.status_code != 200:
        raise RuntimeError(f'Gemini Batch API Error {r.status_code}')
    d = r.json()
    meta = d.get('metadata') or {}
    status = _normalize_state(d.get('state') or meta.get('state'))
    dest = d.get('dest') or meta.get('dest') or meta.get('output') or (d.get('response') or {})
    responses_file = dest.get('responsesFile') or dest.get('responses_file') or dest.get('fileName')
    err = (d.get('error') or {}).get('message') or ''
    return status, responses_file, err


def parse_results(text):
    """結果 JSONL から (thought, result, error) を取り出す。"""
    thought, result, error = '', '', ''
    for raw in text.splitlines():
        raw = raw.strip()
        if not raw:
            continue
        try:
            obj = json.loads(raw)
        except ValueError:
            continue
        if obj.get('error'):
            error = (obj['error'] or {}).get('message') or 'リクエストが失敗しました'
            continue
        parts = ((obj.get('response') or {}).get('candidates') or [{}])[0].get('content', {}).get('parts', [])
        for p in parts:
            if p.get('thought'):
                thought += p.get('text', '')
            elif 'text' in p:
                result += p['text']
    return thought, result, error


def download_results(api_key, responses_file):
    r = requests.get(f'{GEMINI_BASE}/download/v1beta/{responses_file}:download?alt=media',
                     headers=_headers(api_key), timeout=(10, 300))
    if r.status_code != 200:
        raise RuntimeError(f'Gemini 結果ダウンロードエラー {r.status_code}')
    return r.content.decode('utf-8', errors='replace')


def cancel_batch(api_key, name):
    requests.post(f'{GEMINI_BASE}/v1beta/{name}:cancel', headers=_headers(api_key), timeout=(10, 30))


def refresh_job(job, api_key):
    """進行中ジョブを provider に問い合わせて DB を更新する。"""
    if job.status not in ACTIVE_STATES:
        return job
    try:
        status, responses_file, err = fetch_state(api_key, job.provider_job)
        if status == 'running':
            return job
        if status == 'succeeded':
            if not responses_file:
                job.status, job.error = 'failed', '結果ファイルが見つかりません'
            else:
                thought, result, rerr = parse_results(download_results(api_key, responses_file))
                if result or thought:
                    job.status, job.thought_text, job.result_text = 'succeeded', thought, result
                else:
                    job.status, job.error = 'failed', rerr or '結果が空でした'
        else:
            job.status, job.error = status, err or ''
        job.completed_at = datetime.utcnow()
        db.session.commit()
    except Exception as e:
        # 通信エラーなどは次回ポーリングで再試行する（状態は変えない）
        logger.warning(f'batch refresh failed for job {job.id}: {e}')
        db.session.rollback()
    return job


def import_job(job):
    """結果を履歴へ追加する（既存履歴は消さない）。(thought, result) を返す。"""
    core.save_history(job.user_id, job.action_type, job.input_summary or 'Batch', job.thought_text or '', job.result_text or '')
    job.imported = True
    db.session.commit()
    return job.thought_text or '', job.result_text or ''
