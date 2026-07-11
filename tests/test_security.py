import io
import os
import shutil
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

from cryptography.fernet import Fernet


TEST_ROOT = tempfile.mkdtemp(prefix='stt-gemini-security-')
os.environ['SECRET_KEY'] = 'test-secret-key'
os.environ['SQLALCHEMY_DATABASE_URI'] = f"sqlite:///{TEST_ROOT}/test.db"
os.environ['ENCRYPTION_KEY'] = Fernet.generate_key().decode()
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'app'))

with patch.object(threading.Thread, 'start', lambda self: None):
    import app as application


class FakeRedis:
    def __init__(self):
        self.values = {}
        self.hashes = {}
        self.sets = {}

    def incr(self, key):
        self.values[key] = self.values.get(key, 0) + 1
        return self.values[key]

    def expire(self, key, seconds):
        return True

    def set(self, key, value, nx=False, ex=None):
        if nx and key in self.values:
            return False
        self.values[key] = value
        return True

    def get(self, key):
        return self.values.get(key)

    def hset(self, key, mapping):
        self.hashes.setdefault(key, {}).update({name: str(value) for name, value in mapping.items()})

    def hgetall(self, key):
        return dict(self.hashes.get(key, {}))

    def sadd(self, key, value):
        self.sets.setdefault(key, set()).add(value)

    def smembers(self, key):
        return set(self.sets.get(key, set()))

    def srem(self, key, value):
        self.sets.setdefault(key, set()).discard(value)

    def delete(self, *keys):
        for key in keys:
            self.values.pop(key, None)
            self.hashes.pop(key, None)
            self.sets.pop(key, None)

    def eval(self, script, key_count, key, expected):
        if "redis.call('incr'" in script:
            return self.incr(key)
        if self.values.get(key) == expected:
            self.values.pop(key, None)
            return 1
        return 0


class SecurityTests(unittest.TestCase):
    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(TEST_ROOT, ignore_errors=True)

    def setUp(self):
        self.upload_root = os.path.join(TEST_ROOT, 'uploads')
        shutil.rmtree(self.upload_root, ignore_errors=True)
        os.makedirs(self.upload_root)
        application.app.config.update(TESTING=True, UPLOAD_FOLDER=self.upload_root)
        application.redis_client = FakeRedis()
        with application.app.app_context():
            application.db.drop_all()
            application.db.create_all()
            first = application.User(username='first-user', password='hash')
            first.set_api_key('gemini-secret-value')
            second = application.User(username='second-user', password='hash')
            application.db.session.add_all([first, second])
            application.db.session.commit()
            self.first_id = first.id
            self.second_id = second.id

    def authenticate(self, client, user_id, token='csrf-token'):
        with client.session_transaction() as state:
            state['_user_id'] = str(user_id)
            state['_fresh'] = True
            state['csrf_token'] = token
        return token

    def post_chunk(self, client, user_id, content=b'audio', index='0', total='1', upload_id='shared'):
        token = self.authenticate(client, user_id)
        return client.post(
            '/api/upload_chunk',
            data={
                'upload_id': upload_id,
                'chunk_index': index,
                'total_chunks': total,
                'original_filename': 'audio.mp3',
                '_js_challenge': f'valid_{token}',
                'chunk': (io.BytesIO(content), 'chunk'),
            },
            headers={'User-Agent': 'Mozilla/5.0', 'X-CSRFToken': token},
        )

    def test_chunk_uploads_are_isolated_by_user(self):
        first_client = application.app.test_client()
        second_client = application.app.test_client()

        self.assertEqual(self.post_chunk(first_client, self.first_id, b'first').status_code, 200)
        self.assertEqual(self.post_chunk(second_client, self.second_id, b'second').status_code, 200)

        first_path = os.path.join(
            self.upload_root, '_chunks', f'user_{self.first_id}', 'shared', 'chunk_000000'
        )
        second_path = os.path.join(
            self.upload_root, '_chunks', f'user_{self.second_id}', 'shared', 'chunk_000000'
        )
        with open(first_path, 'rb') as first_file, open(second_path, 'rb') as second_file:
            self.assertEqual(first_file.read(), b'first')
            self.assertEqual(second_file.read(), b'second')

    def test_chunk_bounds_and_size_are_enforced(self):
        client = application.app.test_client()
        self.assertEqual(self.post_chunk(client, self.first_id, index='-1').status_code, 400)
        self.assertEqual(
            self.post_chunk(client, self.first_id, total=str(application.MAX_CHUNKS + 1)).status_code,
            400,
        )
        with patch.object(application, 'MAX_CHUNK_BYTES', 3):
            self.assertEqual(self.post_chunk(client, self.first_id, content=b'toolarge').status_code, 413)

    def test_upload_metadata_cannot_change_mid_upload(self):
        client = application.app.test_client()
        self.assertEqual(self.post_chunk(client, self.first_id, total='2').status_code, 200)
        self.assertEqual(self.post_chunk(client, self.first_id, total='3').status_code, 400)

    def test_chunk_quota_ignores_metadata_and_blocks_late_replacement(self):
        client = application.app.test_client()
        with patch.object(application, 'MAX_XAI_AUDIO_BYTES', 5):
            self.assertEqual(self.post_chunk(client, self.first_id, content=b'12345').status_code, 200)
        chunks_dir = os.path.join(
            self.upload_root, '_chunks', f'user_{self.first_id}', 'shared'
        )
        open(os.path.join(chunks_dir, '.complete'), 'w').close()
        self.assertEqual(self.post_chunk(client, self.first_id, content=b'other').status_code, 409)

    def test_settings_never_render_decrypted_api_key(self):
        client = application.app.test_client()
        self.authenticate(client, self.first_id)
        response = client.get('/settings', headers={'User-Agent': 'Mozilla/5.0'})
        self.assertEqual(response.status_code, 200)
        self.assertNotIn(b'gemini-secret-value', response.data)
        self.assertEqual(response.headers['Cache-Control'], 'no-store')

    def test_logout_requires_post_and_csrf(self):
        client = application.app.test_client()
        token = self.authenticate(client, self.first_id)
        self.assertEqual(client.get('/logout', headers={'User-Agent': 'Mozilla/5.0'}).status_code, 405)
        response = client.post(
            '/logout',
            data={'csrf_token': token},
            headers={'User-Agent': 'Mozilla/5.0'},
        )
        self.assertEqual(response.status_code, 302)

    def test_unsupported_upload_extension_is_rejected(self):
        client = application.app.test_client()
        token = self.authenticate(client, self.first_id)
        response = client.post(
            '/transcribe',
            data={
                '_js_challenge': f'valid_{token}',
                'audio_file': (io.BytesIO(b'not audio'), 'payload.html'),
            },
            headers={'User-Agent': 'Mozilla/5.0', 'X-CSRFToken': token},
        )
        self.assertEqual(response.status_code, 400)

    def test_security_headers_disable_eval_and_framing(self):
        response = application.app.test_client().get('/welcome', headers={'User-Agent': 'Mozilla/5.0'})
        self.assertEqual(response.headers['X-Frame-Options'], 'DENY')
        self.assertNotIn("'unsafe-eval'", response.headers['Content-Security-Policy'])
        self.assertIn('max-age=31536000', response.headers['Strict-Transport-Security'])

    def test_gemini_key_is_sent_in_header_not_url(self):
        response = Mock(status_code=200)
        response.iter_lines.return_value = []
        with patch.object(application.requests, 'post', return_value=response) as post:
            with patch.object(application, 'update_task'), patch.object(application, 'save_history'):
                application.process_gemini_background(
                    'task-id', 'private-key', {'contents': []}, self.first_id, 'test', 'test'
                )
        url = post.call_args.args[0]
        headers = post.call_args.kwargs['headers']
        self.assertNotIn('private-key', url)
        self.assertEqual(headers['x-goog-api-key'], 'private-key')

    def test_only_one_active_task_is_allowed_per_user(self):
        task_id = application.create_task(self.first_id, 'test', 'test', 'gemini-3.5-flash')
        with self.assertRaises(application.ActiveTaskError):
            application.create_task(self.first_id, 'test', 'test', 'gemini-3.5-flash')
        application.update_task(task_id, status='done')
        application.create_task(self.first_id, 'test', 'test', 'gemini-3.5-flash')


if __name__ == '__main__':
    unittest.main()
