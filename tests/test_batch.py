"""Batch 機能のテスト（Gemini Batch API への HTTP は mock で遮断）。test_security.py と同じ環境構築。"""
import atexit
import io
import os
import shutil
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

from cryptography.fernet import Fernet

TEST_ROOT = tempfile.mkdtemp(prefix='stt-gemini-batch-')
# app モジュールは discover で他のテストと共有される(先に import した側の DB が使われる)ため、後始末はプロセス終了時に行う
atexit.register(shutil.rmtree, TEST_ROOT, True)
os.environ['SECRET_KEY'] = 'test-secret-key'
os.environ['SQLALCHEMY_DATABASE_URI'] = f"sqlite:///{TEST_ROOT}/test.db"
os.environ['ENCRYPTION_KEY'] = Fernet.generate_key().decode()
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'app'))

with patch.object(threading.Thread, 'start', lambda self: None):
    import app as application
    import batch


class BatchTests(unittest.TestCase):
    def setUp(self):
        application.app.config.update(TESTING=True)
        with application.app.app_context():
            application.db.drop_all()
            application.db.create_all()
            u1 = application.User(username='u1', password='hash')
            u1.set_api_key('gemini-key')
            u2 = application.User(username='u2', password='hash')
            u2.set_api_key('gemini-key')
            application.db.session.add_all([u1, u2])
            application.db.session.commit()
            self.u1, self.u2 = u1.id, u2.id

    def auth(self, client, uid, token='tok'):
        with client.session_transaction() as s:
            s['_user_id'] = str(uid)
            s['_fresh'] = True
            s['csrf_token'] = token
        return {'User-Agent': 'Mozilla/5.0', 'X-CSRFToken': token}

    def add_job(self, uid, status='succeeded', imported=False):
        with application.app.app_context():
            job = batch.BatchJob(user_id=uid, provider_job='batches/1', model='gemini-3.5-flash', action_type='transcribe',
                                 input_summary='x', status=status, result_text='結果', thought_text='')
            job.imported = imported
            application.db.session.add(job)
            application.db.session.commit()
            return job.id

    def test_non_batch_model_rejected(self):
        c = application.app.test_client()
        h = self.auth(c, self.u1)
        r = c.post('/api/batches', data={'model': 'gpt-transcribe', 'action': 'transcribe', '_js_challenge': 'valid_tok',
                                         'audio_file': (io.BytesIO(b'a'), 'a.mp3')}, headers=h)
        self.assertEqual(r.status_code, 400)

    def test_submit_creates_job(self):
        c = application.app.test_client()
        h = self.auth(c, self.u1)
        with patch.object(batch, 'upload_file', return_value={'name': 'files/a', 'uri': 'u'}), \
             patch.object(batch, 'wait_file_active', side_effect=lambda k, f: f), \
             patch.object(batch, 'create_batch', return_value='batches/abc') as cb:
            r = c.post('/api/batches', data={'model': 'gemini-3.5-flash', 'action': 'transcribe', '_js_challenge': 'valid_tok',
                                             'audio_file': (io.BytesIO(b'audio'), 'a.mp3')}, headers=h)
        self.assertEqual(r.status_code, 200, r.get_data(as_text=True))
        self.assertEqual(r.get_json()['status'], 'running')
        parts = cb.call_args[0][2]['contents'][0]['parts']
        self.assertIn('file_data', parts[1])

    def test_import_keeps_existing_history(self):
        jid = self.add_job(self.u1)
        with application.app.app_context():
            application.db.session.add(application.History(user_id=self.u1, action_type='transcribe', result_text='old'))
            application.db.session.commit()
        c = application.app.test_client()
        h = self.auth(c, self.u1)
        r = c.post(f'/api/batches/{jid}/import', json={'_js_challenge': 'valid_tok'}, headers=h)
        self.assertEqual(r.status_code, 200, r.get_data(as_text=True))
        self.assertEqual(r.get_json()['result'], '結果')
        with application.app.app_context():
            texts = sorted(x.result_text for x in application.History.query.filter_by(user_id=self.u1))
            self.assertEqual(texts, ['old', '結果'])
            self.assertTrue(application.db.session.get(batch.BatchJob, jid).imported)

    def test_jobs_are_isolated_between_users(self):
        jid = self.add_job(self.u1)
        c = application.app.test_client()
        h = self.auth(c, self.u2)
        self.assertEqual(c.post(f'/api/batches/{jid}/import', json={'_js_challenge': 'valid_tok'}, headers=h).status_code, 404)
        self.assertEqual(c.delete(f'/api/batches/{jid}', json={'_js_challenge': 'valid_tok'}, headers=h).status_code, 404)
        with patch.object(batch, 'refresh_job', side_effect=lambda j, k: j):
            self.assertEqual(c.get('/api/batches', headers=h).get_json(), [])

    def test_running_job_cannot_be_imported(self):
        jid = self.add_job(self.u1, status='running')
        c = application.app.test_client()
        h = self.auth(c, self.u1)
        self.assertEqual(c.post(f'/api/batches/{jid}/import', json={'_js_challenge': 'valid_tok'}, headers=h).status_code, 400)

    def test_refresh_job_succeeds_and_parses_results(self):
        jid = self.add_job(self.u1, status='running')
        jsonl = '{"key":"request-1","response":{"candidates":[{"content":{"parts":[{"thought":true,"text":"t"},{"text":"本文"}]}}]}}\n'
        with application.app.app_context():
            job = application.db.session.get(batch.BatchJob, jid)
            job.result_text = None
            with patch.object(batch, 'fetch_state', return_value=('succeeded', 'files/out', '')), \
                 patch.object(batch, 'download_results', return_value=jsonl):
                batch.refresh_job(job, 'k')
            self.assertEqual((job.status, job.result_text, job.thought_text), ('succeeded', '本文', 't'))

    def test_state_normalization(self):
        self.assertEqual(batch._normalize_state('JOB_STATE_SUCCEEDED'), 'succeeded')
        self.assertEqual(batch._normalize_state('BATCH_STATE_PENDING'), 'running')
        self.assertEqual(batch._normalize_state('JOB_STATE_EXPIRED'), 'expired')


if __name__ == '__main__':
    unittest.main()
