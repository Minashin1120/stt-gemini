import os
import sys
import json
import requests
import base64
import time
import threading
import secrets
import uuid
import logging
import fcntl
from urllib.parse import urlencode
from datetime import datetime, timedelta
from flask import Flask, render_template, request, redirect, url_for, flash, jsonify, Response, stream_with_context, session, send_from_directory
from flask_login import LoginManager, UserMixin, login_user, login_required, logout_user, current_user
from flask_sqlalchemy import SQLAlchemy
from flask_sock import Sock
from cryptography.fernet import Fernet
from werkzeug.security import generate_password_hash, check_password_hash
from werkzeug.utils import secure_filename
from werkzeug.middleware.proxy_fix import ProxyFix
from dotenv import load_dotenv
from sqlalchemy import inspect
from sqlalchemy.exc import IntegrityError

load_dotenv()

# `python app.py` で直接起動した場合も、分割モジュールの `import app` が同じモジュールを指すようにする
if __name__ == '__main__':
    sys.modules.setdefault('app', sys.modules[__name__])

# Setup logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s [%(levelname)s] %(message)s')
logger = logging.getLogger(__name__)

app = Flask(__name__)
app.wsgi_app = ProxyFix(app.wsgi_app, x_for=1, x_proto=1)
app.config['SECRET_KEY'] = os.getenv('SECRET_KEY')
app.config['SQLALCHEMY_DATABASE_URI'] = os.getenv('SQLALCHEMY_DATABASE_URI')
app.config['UPLOAD_FOLDER'] = os.path.join(app.root_path, 'uploads')
app.config['MAX_CONTENT_LENGTH'] = 100 * 1024 * 1024  # 100MBまで単一アップロード、超えると分割
# セッション/Remember Me クッキーは 30 日間保持。
# 以前は 1 日だったため、利用間隔が空くと自動ログアウトしていた（Windows Chrome 等で顕在化）。
# SESSION_REFRESH_EACH_REQUEST により、アクティブ利用中はセッションクッキーの期限が毎回延長される。
app.config['PERMANENT_SESSION_LIFETIME'] = timedelta(days=30)
app.config['SESSION_COOKIE_SECURE'] = True
app.config['SESSION_COOKIE_HTTPONLY'] = True
app.config['SESSION_COOKIE_SAMESITE'] = 'Lax'
app.config['SESSION_REFRESH_EACH_REQUEST'] = True
app.config['REMEMBER_COOKIE_SECURE'] = True
app.config['REMEMBER_COOKIE_HTTPONLY'] = True
app.config['REMEMBER_COOKIE_SAMESITE'] = 'Lax'
app.config['REMEMBER_COOKIE_DURATION'] = timedelta(days=30)
app.config['REMEMBER_COOKIE_REFRESH_EACH_REQUEST'] = True

MAX_GEMINI_AUDIO_BYTES = 100 * 1024 * 1024
MAX_XAI_AUDIO_BYTES = 500 * 1024 * 1024
MAX_OPENAI_AUDIO_BYTES = 25 * 1024 * 1024  # 25MB
MAX_CHUNK_BYTES = 6 * 1024 * 1024
MAX_CHUNKS = 100
MAX_INCOMPLETE_UPLOADS = 3
MAX_API_KEY_LENGTH = 512
MAX_TEXT_LENGTH = 200_000
MAX_INSTRUCTION_LENGTH = 20_000
MAX_WORD_LIST_IMPORT_BYTES = 1024 * 1024
MAX_WORD_LIST_IMPORT_SETS = 200
MAX_WORD_LIST_IMPORT_WORDS = 10_000
ALLOWED_AUDIO_EXTENSIONS = {
    '.mp3': 'audio/mpeg',
    '.wav': 'audio/wav',
    '.m4a': 'audio/mp4',
    '.mp4': 'audio/mp4',
    '.webm': 'audio/webm',
    '.ogg': 'audio/ogg',
}
ALLOWED_THINKING_LEVELS = {'LOW', 'MEDIUM', 'HIGH'}

db = SQLAlchemy(app)
login_manager = LoginManager()
login_manager.init_app(app)
login_manager.login_view = 'welcome'
sock = Sock(app)

fernet = Fernet(os.getenv('ENCRYPTION_KEY').encode())

# Run database migration for new columns at module load (needed for Gunicorn)
with app.app_context():
    try:
        inspector = inspect(db.engine)
        columns = [col['name'] for col in inspector.get_columns('user')]
        if 'encrypted_xai_api_key' not in columns:
            db.session.execute(db.text('ALTER TABLE user ADD COLUMN encrypted_xai_api_key TEXT NULL'))
            db.session.commit()
            logger.info("Database migration: Added encrypted_xai_api_key column to user table")
        if 'encrypted_openai_api_key' not in columns:
            db.session.execute(db.text('ALTER TABLE user ADD COLUMN encrypted_openai_api_key TEXT NULL'))
            db.session.commit()
            logger.info("Database migration: Added encrypted_openai_api_key column to user table")
    except Exception as e:
        # Gunicorn multi-worker 環境では競合が発生し得るが無害
        if 'Duplicate column' not in str(e):
            logger.error(f"Database migration error: {e}")

# --- Redis Client ---
import redis as redis_module
redis_client = redis_module.Redis(host='127.0.0.1', port=6379, decode_responses=True)

ALLOWED_MODELS = {
    'gemini-3.6-flash',
    'gemini-3.5-flash',
    'gemini-3.5-flash-lite',
    'gemini-3-flash-preview',
    'gemini-3.1-flash-lite',
    'gemini-3.5-transcribe',
    'gemini-3.5-transcribe-live',
    'grok-stt',
    'grok-live-transcribe',
    'gpt-transcribe',
    'gpt-live-transcribe',
    'whisper-1',
    'gpt-4o-transcribe',
    'gpt-4o-mini-transcribe',
    'gpt-4o-transcribe-diarize',
    'gpt-realtime-whisper',
}

OPENAI_FILE_STT_MODELS = {
    'gpt-transcribe', 'whisper-1', 'gpt-4o-transcribe',
    'gpt-4o-mini-transcribe', 'gpt-4o-transcribe-diarize',
}
OPENAI_LIVE_STT_MODELS = {'gpt-live-transcribe', 'gpt-realtime-whisper'}
OPENAI_STT_MODELS = OPENAI_FILE_STT_MODELS | OPENAI_LIVE_STT_MODELS
GEMINI_STT_MODELS = {'gemini-3.5-transcribe', 'gemini-3.5-transcribe-live'}

# --- Grok Live (WebSocket streaming) settings ---
GROK_LIVE_ALLOWED_ORIGINS = {f"https://{os.getenv('GROK_LIVE_HOST', 'stt-gemini.minashin1120.com')}"}
GROK_LIVE_MAX_SECONDS = 1800
GROK_LIVE_TASK_REFRESH_SECS = 60

def is_plausible_xai_api_key(api_key):
    """現行のxAIコンソールが発行するAPIキーの最低限の形式を確認する。"""
    normalized = api_key.strip() if api_key else ''
    return len(normalized) > 4 and normalized.startswith('xai-')

def validate_model(model_name):
    return model_name if model_name in ALLOWED_MODELS else 'gemini-3.5-flash'

# --- Task Management (Redis-backed for crash recovery) ---
TASK_TTL = 86400  # 24h
ACTIVE_TASK_TTL = 1200

class ActiveTaskError(Exception):
    pass

@app.errorhandler(ActiveTaskError)
def handle_active_task_error(error):
    return jsonify({'error': '別の処理が実行中です。完了後に再度お試しください。'}), 409

def create_task(user_id, action_type, input_summary, model):
    task_id = str(uuid.uuid4())
    task_key = f"task:{task_id}"
    active_key = f"user:{user_id}:active_task"
    if not redis_client.set(active_key, task_id, nx=True, ex=ACTIVE_TASK_TTL):
        raise ActiveTaskError()
    now = time.time()
    try:
        redis_client.hset(task_key, mapping={
            'status': 'running',
            'user_id': str(user_id),
            'action_type': action_type,
            'input_summary': input_summary or '',
            'thought': '',
            'result': '',
            'model': model,
            'server_instance': str(os.getppid()),
            'worker_pid': str(os.getpid()),
            'created_at': now,
            'updated_at': now,
        })
        redis_client.expire(task_key, TASK_TTL)
        redis_client.sadd(f"user:{user_id}:tasks", task_id)
        redis_client.expire(f"user:{user_id}:tasks", TASK_TTL)
    except Exception:
        redis_client.eval(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) end return 0",
            1, active_key, task_id,
        )
        raise
    return task_id

def update_task(task_id, **kwargs):
    task_key = f"task:{task_id}"
    current_status = redis_client.hgetall(task_key).get('status')
    if current_status == 'cancelled' and kwargs.get('status') != 'cancelled':
        return
    kwargs['updated_at'] = time.time()
    redis_client.hset(task_key, mapping=kwargs)
    task = redis_client.hgetall(task_key)
    user_id = task.get('user_id')
    if user_id:
        active_key = f"user:{user_id}:active_task"
        if kwargs.get('status') in {'done', 'error', 'cancelled'}:
            redis_client.eval(
                "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) end return 0",
                1, active_key, task_id,
            )
        elif redis_client.get(active_key) == task_id:
            redis_client.expire(active_key, ACTIVE_TASK_TTL)

def cancel_task(task_id, user_id, message='処理を停止しました'):
    task = get_task(task_id)
    if not task or task.get('user_id') != str(user_id):
        return False
    update_task(task_id, status='cancelled', error=message)
    return True

def task_is_cancelled(task_id):
    return get_task(task_id).get('status') == 'cancelled'

def process_is_alive(pid):
    try:
        os.kill(int(pid), 0)
        return True
    except (TypeError, ValueError, ProcessLookupError, PermissionError):
        return False

def get_task(task_id):
    return redis_client.hgetall(f"task:{task_id}")

def delete_task(task_id):
    task = get_task(task_id)
    if task:
        uid = task.get('user_id')
        if uid:
            redis_client.srem(f"user:{uid}:tasks", task_id)
            redis_client.eval(
                "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) end return 0",
                1, f"user:{uid}:active_task", task_id,
            )
        redis_client.delete(f"task:{task_id}")

def consume_rate_limit(redis_key, max_requests, window_seconds):
    current = redis_client.eval(
        "local n = redis.call('incr', KEYS[1]); "
        "if n == 1 then redis.call('expire', KEYS[1], ARGV[1]); end; return n",
        1, redis_key, window_seconds,
    )
    return current <= max_requests

def check_rate_limit(key_prefix, max_requests, window_seconds):
    client_ip = request.remote_addr or 'unknown'
    return consume_rate_limit(f"ratelimit:{key_prefix}:{client_ip}", max_requests, window_seconds)

def check_user_model_rate_limit():
    return consume_rate_limit(f"ratelimit:model:user:{current_user.id}", 60, 3600)

def reject_if_active_task():
    if redis_client.get(f"user:{current_user.id}:active_task"):
        raise ActiveTaskError()

CSRF_EXEMPT_ENDPOINTS = {
    'static',
    'favicon',
}

def get_csrf_token():
    token = session.get('csrf_token')
    if not token:
        token = secrets.token_urlsafe(32)
        session['csrf_token'] = token
    return token

@app.context_processor
def inject_csrf_token():
    return {'csrf_token': get_csrf_token}

@app.context_processor
def inject_static_helpers():
    def static_v(filename):
        """静的ファイルの URL に更新時刻を付け、編集後にブラウザキャッシュが残らないようにする。"""
        try:
            version = int(os.path.getmtime(os.path.join(app.static_folder, filename)))
        except OSError:
            version = 0
        return url_for('static', filename=filename, v=version)
    return {'static_v': static_v}

def is_atypical_client(req):
    # 1. User-Agent keywords
    ua_raw = req.headers.get('User-Agent') or ''
    ua = ua_raw.lower()
    
    # 一般的なブラウザに含まれるキーワード
    common_browser_keywords = ['mozilla', 'chrome', 'safari', 'applewebkit', 'edge', 'trident', 'firefox']
    is_common_browser = any(kw in ua for kw in common_browser_keywords)
    
    # 明らかに自動化ツールなもの
    atypical_ua_keywords = ['python-requests', 'curl', 'go-http-client', 'postmanruntime', 'insomnia', 'httpie', 'wget', 'urllib', 'axios', 'phantomjs', 'selenium', 'playwright', 'puppeteer']
    
    # 判定とロギング
    if not ua:
        if req.method != 'GET':
            logger.warning(f"Atypical client: Missing User-Agent on {req.method} {req.path}")
            return True
        return False
        
    for kw in atypical_ua_keywords:
        if kw in ua:
            # 'headless' は除外 (Google Botなどが含まれる可能性があるため、より具体的に)
            # ただし 'headless' が単体で入っている場合は不審
            if kw == 'headless' and 'chrome' in ua:
                # HeadlessChrome は自動化の強い兆候
                logger.warning(f"Atypical client: Automation tool detected in UA: {kw} (UA: {ua_raw})")
                return True
            logger.warning(f"Atypical client: Automation tool detected in UA: {kw} (UA: {ua_raw})")
            return True
    
    # 一般的なブラウザキーワードが含まれていない場合は不審とする (POST等のみ)
    if not is_common_browser and req.method != 'GET':
        logger.warning(f"Atypical client: No common browser keywords in UA: {ua_raw}")
        return True

    # 2. JavaScript Challenge Check (Required for all POSTs)
    if req.method in {'POST', 'PUT', 'PATCH', 'DELETE'}:
        js_challenge = req.form.get('_js_challenge')
        
        # JSONリクエストの場合も考慮
        if not js_challenge and req.is_json:
            js_challenge = (req.get_json(silent=True) or {}).get('_js_challenge')
            
        csrf_token = session.get('csrf_token')
        # Expecting 'valid_<csrf_token>' set by client-side JS
        if not js_challenge or js_challenge != f"valid_{csrf_token}":
            logger.warning("Atypical client: JS challenge failed or missing")
            return True

        # Honey-pot field check (Highly sensitive)
        if req.form.get('_honey_field'):
            logger.warning(f"Atypical client: Honey-pot field filled: {req.form.get('_honey_field')}")
            return True

    return False

def is_truthy(value):
    return str(value).strip().lower() in {"1", "true", "yes", "on"}

@app.before_request
def check_security():
    # 常にクライアントの整合性をチェック
    atypical = is_atypical_client(request)
    
    if current_user.is_authenticated:
        # ロック済みユーザーは即座にログアウト
        if current_user.is_locked:
            logout_user()
            flash('アカウントがロックされています。管理者にお問い合わせください。')
            return redirect(url_for('login'))
        
        # 操作中に「おかしな点」があれば警告を表示（ログアウトはさせない）
        if atypical:
            # ログアウトさせず、警告のみにする (開発中の誤検知対策)
            # flash('不審な操作が検知されました。ブラウザの設定や拡張機能を確認してください。')
            pass
    else:
        # 未ログインでも不審なリクエストは遮断 (POSTのみにするなど緩和)
        # ただしログイン・登録・解除申請ルートは個別に制御するため除外
        if atypical and request.method != 'GET' and request.endpoint not in {'login', 'register', 'request_unlock', 'welcome'}:
            return "Access Denied: Suspected Automated Access", 403

@app.before_request
def protect_csrf():
    if request.method in {'POST', 'PUT', 'PATCH', 'DELETE'} and request.endpoint not in CSRF_EXEMPT_ENDPOINTS:
        session_token = session.get('csrf_token')
        request_token = request.form.get('csrf_token') or request.headers.get('X-CSRFToken') or request.headers.get('X-CSRF-Token')
        if not session_token or not request_token or not secrets.compare_digest(request_token, session_token):
            return jsonify({'error': 'CSRF validation failed'}), 400

@app.after_request
def add_security_headers(response):
    response.headers['X-Content-Type-Options'] = 'nosniff'
    response.headers['X-Frame-Options'] = 'DENY'
    response.headers['Referrer-Policy'] = 'same-origin'
    response.headers['Strict-Transport-Security'] = 'max-age=31536000; includeSubDomains'
    response.headers['Permissions-Policy'] = 'microphone=(self), camera=(), geolocation=()'
    response.headers['Cross-Origin-Resource-Policy'] = 'same-origin'
    csp = (
        "default-src 'self'; "
        "script-src 'self' https://cdn.jsdelivr.net https://challenges.cloudflare.com 'unsafe-inline'; "
        "style-src 'self' https://cdn.jsdelivr.net https://fonts.googleapis.com 'unsafe-inline'; "
        "font-src 'self' https://fonts.gstatic.com https://cdn.jsdelivr.net data:; "
        "img-src 'self' data:; "
        "connect-src 'self'; "
        "frame-src https://challenges.cloudflare.com; "
        "media-src 'self' blob:; "
        "base-uri 'self'; "
        "form-action 'self';"
    )
    response.headers['Content-Security-Policy'] = csp
    if current_user.is_authenticated or request.path.startswith('/api/'):
        response.headers['Cache-Control'] = 'no-store'
    return response

# --- Models ---
class User(UserMixin, db.Model):
    id = db.Column(db.Integer, primary_key=True)
    username = db.Column(db.String(150), unique=True, nullable=False)
    password = db.Column(db.String(255), nullable=False)
    encrypted_api_key = db.Column(db.Text, nullable=True)
    encrypted_xai_api_key = db.Column(db.Text, nullable=True)
    encrypted_openai_api_key = db.Column(db.Text, nullable=True)
    retention_minutes = db.Column(db.Integer, default=10, nullable=False)
    is_locked = db.Column(db.Boolean, default=False)
    unlock_requested = db.Column(db.Boolean, default=False)

    def set_api_key(self, api_key):
        if api_key:
            self.encrypted_api_key = fernet.encrypt(api_key.encode()).decode()
        else:
            self.encrypted_api_key = None

    def get_api_key(self):
        if self.encrypted_api_key:
            try: return fernet.decrypt(self.encrypted_api_key.encode()).decode()
            except: return None
        return None

    def set_xai_api_key(self, api_key):
        if api_key:
            self.encrypted_xai_api_key = fernet.encrypt(api_key.encode()).decode()
        else:
            self.encrypted_xai_api_key = None

    def get_xai_api_key(self):
        if self.encrypted_xai_api_key:
            try: return fernet.decrypt(self.encrypted_xai_api_key.encode()).decode()
            except: return None
        return None

    def set_openai_api_key(self, api_key):
        if api_key:
            self.encrypted_openai_api_key = fernet.encrypt(api_key.encode()).decode()
        else:
            self.encrypted_openai_api_key = None

    def get_openai_api_key(self):
        if self.encrypted_openai_api_key:
            try: return fernet.decrypt(self.encrypted_openai_api_key.encode()).decode()
            except: return None
        return None

class History(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey('user.id'), nullable=False)
    action_type = db.Column(db.String(50), nullable=False) # transcribe, improve, reanalyze
    input_summary = db.Column(db.Text, nullable=True) # ユーザー指示やファイル名
    thought_text = db.Column(db.Text, nullable=True)  # モデルの思考
    result_text = db.Column(db.Text, nullable=True)   # 最終結果
    timestamp = db.Column(db.DateTime, default=datetime.utcnow)

class WordSet(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    user_id = db.Column(db.Integer, db.ForeignKey('user.id'), nullable=False)
    name = db.Column(db.String(100), nullable=False)
    is_active = db.Column(db.Boolean, default=False)
    words = db.relationship('Word', backref='word_set', cascade='all, delete-orphan')

class Word(db.Model):
    id = db.Column(db.Integer, primary_key=True)
    set_id = db.Column(db.Integer, db.ForeignKey('word_set.id'), nullable=False)
    reading = db.Column(db.String(255), nullable=False)
    replacement = db.Column(db.String(255), nullable=False)

@login_manager.user_loader
def load_user(user_id):
    return db.session.get(User, int(user_id))

# --- Helpers ---
import subprocess
import shutil
from email.mime.text import MIMEText

# Turnstile検証はログイン等のクリティカルパスで毎回呼ばれるため、
# HTTP接続を再利用するセッションを使用してレイテンシを短縮する。
_turnstile_session = requests.Session()

def verify_turnstile(token):
    secret = os.getenv('TURNSTILE_SECRET_KEY')
    if not secret or not token:
        return False
    try:
        res = _turnstile_session.post(
            'https://challenges.cloudflare.com/turnstile/v0/siteverify',
            data={'secret': secret, 'response': token},
            timeout=5
        )
        return res.json().get('success', False)
    except:
        return False

def notify_admin_unlock(username):
    admin_email = os.getenv('MAIL_ADMIN_RECIPIENT', 'minashin.official@gmail.com')
    body = f"ユーザー '{username}' からアカウントのロック解除申請がありました。\n管理パネルまたはデータベースから確認してください。"
    msg = MIMEText(body)
    safe_username = username.replace('\r', '').replace('\n', '')
    msg['Subject'] = f"[stt-gemini] ロック解除申請: {safe_username}"
    msg['From'] = os.getenv('MAIL_DEFAULT_SENDER', 'noreply@stt-gemini.minashin1120.com')
    msg['To'] = admin_email

    try:
        # Exim4 (sendmail互換コマンド) を使用
        subprocess.run(
            ['/usr/sbin/sendmail', '-t', '-oi'],
            input=msg.as_bytes(),
            check=True,
            timeout=15,
        )
    except Exception as e:
        logger.error(f"Exim4 email notification failed: {e}")

@app.context_processor
def inject_site_keys():
    return {'turnstile_site_key': os.getenv('TURNSTILE_SITE_KEY')}

def get_word_list_context(user_id):
    active_sets = WordSet.query.filter_by(user_id=user_id, is_active=True).all()
    if not active_sets:
        return ""
    
    context = "\n--- CUSTOM VOCABULARY (READING -> REPLACEMENT) ---\n"
    context += "If you hear something similar to the reading on the left, strictly use the word on the right.\n"
    for s in active_sets:
        for w in s.words:
            context += f"- {w.reading} -> {w.replacement}\n"
    context += "--------------------------------------------------\n"
    return context

def apply_word_replacements(user_id, text):
    if not text:
        return text
    try:
        with app.app_context():
            active_sets = WordSet.query.filter_by(user_id=user_id, is_active=True).all()
            if not active_sets:
                return text
            import re
            for s in active_sets:
                for w in s.words:
                    if w.reading and w.replacement:
                        text = re.sub(re.escape(w.reading), w.replacement, text, flags=re.IGNORECASE)
    except Exception as e:
        logger.warning(f"Word replacement error: {e}")
    return text

def get_audio_metadata(filename, mimetype=None):
    safe_name = secure_filename(filename or "")
    _, ext = os.path.splitext(safe_name)
    ext = ext.lower()
    return (ext, ALLOWED_AUDIO_EXTENSIONS[ext]) if ext in ALLOWED_AUDIO_EXTENSIONS else (None, None)

def get_thinking_level(value):
    level = str(value or 'LOW').upper()
    return level if level in ALLOWED_THINKING_LEVELS else 'LOW'

def generate_audio_filename(user_id, extension):
    return f"user_{user_id}_{time.time_ns()}_{secrets.token_hex(4)}{extension}"

def save_uploaded_audio_file(file, is_append):
    """録音/アップロードされた音声ファイルを保存し、(filepath, mime_type) を返す。
    新規録音時は以前の履歴・音声ファイルをクリアする。対応していない形式なら None を返す。"""
    ext, mime_type = get_audio_metadata(file.filename, file.mimetype)
    if not ext:
        return None, None, None

    if not is_truthy(is_append):
        History.query.filter_by(user_id=current_user.id).delete()
        db.session.commit()
        user_prefix = f"user_{current_user.id}_"
        if os.path.exists(app.config['UPLOAD_FOLDER']):
            for f in os.listdir(app.config['UPLOAD_FOLDER']):
                if f.startswith(user_prefix):
                    try:
                        os.remove(os.path.join(app.config['UPLOAD_FOLDER'], f))
                    except Exception as e:
                        logger.warning(f"clear_on_new file removal error: {e}")
        session.pop('last_audio_file', None)
        session.pop('last_audio_mime', None)

    filename = generate_audio_filename(current_user.id, ext)
    if not os.path.exists(app.config['UPLOAD_FOLDER']):
        os.makedirs(app.config['UPLOAD_FOLDER'])

    filepath = os.path.join(app.config['UPLOAD_FOLDER'], filename)
    file.save(filepath)
    session['last_audio_file'] = filename
    session['last_audio_mime'] = mime_type
    return filename, filepath, mime_type

def resolve_user_upload_path(filename, user_id):
    if not filename or not filename.startswith(f"user_{user_id}_"):
        return None

    upload_dir = os.path.realpath(app.config['UPLOAD_FOLDER'])
    full_path = os.path.realpath(os.path.join(upload_dir, filename))
    if not full_path.startswith(upload_dir + os.sep):
        return None
    return full_path

def cleanup_old_data():
    while True:
        try:
            with app.app_context():
                users = User.query.all()
                now_ts = time.time()
                now_dt = datetime.utcnow()
                
                for user in users:
                    retention_seconds = user.retention_minutes * 60
                    retention_limit_dt = now_dt - timedelta(minutes=user.retention_minutes)
                    
                    # Clean up History
                    History.query.filter(History.user_id == user.id, History.timestamp < retention_limit_dt).delete()
                    
                    # Clean up Files
                    user_prefix = f"user_{user.id}_"
                    if os.path.exists(app.config['UPLOAD_FOLDER']):
                        for f in os.listdir(app.config['UPLOAD_FOLDER']):
                            if f.startswith(user_prefix):
                                f_path = os.path.join(app.config['UPLOAD_FOLDER'], f)
                                try:
                                    if os.path.isfile(f_path) and os.stat(f_path).st_mtime < now_ts - retention_seconds:
                                        os.remove(f_path)
                                except Exception as file_err:
                                    logger.warning(f"File removal error: {file_err}")
                
                db.session.commit()
                
                # Clean up stale chunk directories (>1 hour old)
                chunks_root = os.path.join(app.config['UPLOAD_FOLDER'], '_chunks')
                if os.path.exists(chunks_root):
                    for d in os.listdir(chunks_root):
                        d_path = os.path.join(chunks_root, d)
                        try:
                            if os.path.isdir(d_path) and os.stat(d_path).st_mtime < now_ts - 3600:
                                shutil.rmtree(d_path, ignore_errors=True)
                        except Exception as chunk_err:
                            logger.warning(f"Chunk cleanup error: {chunk_err}")
        except Exception as e:
            logger.error(f"Cleanup error: {e}")
            try: db.session.rollback()
            except: pass
        time.sleep(60)

threading.Thread(target=cleanup_old_data, daemon=True).start()

def get_active_history_context(user_id):
    """
    ユーザー設定の保持時間内の履歴を取得し、モデル用のコンテキスト文字列を生成する。
    """
    user = db.session.get(User, user_id)
    retention_minutes = user.retention_minutes if user else 10
    
    # 最新の履歴を取得して時間をチェック
    last_entry = History.query.filter_by(user_id=user_id).order_by(History.timestamp.desc()).first()
    
    if not last_entry:
        return ""
    
    # 最後の操作から保持時間以上経過していればコンテキストは渡さない
    if (datetime.utcnow() - last_entry.timestamp).total_seconds() > (retention_minutes * 60):
        return ""

    # 有効な履歴を取得 (古い順)
    limit_dt = datetime.utcnow() - timedelta(minutes=retention_minutes)
    histories = History.query.filter_by(user_id=user_id).filter(History.timestamp > limit_dt).order_by(History.timestamp.asc()).all()
    
    context_str = "\n--- CONTEXT: PREVIOUS INTERACTION HISTORY ---\n"
    for h in histories:
        context_str += f"[Action: {h.action_type}] ({h.timestamp.strftime('%H:%M:%S')})\n"
        if h.input_summary:
            context_str += f"Input/Instruction: {h.input_summary}\n"
        if h.thought_text:
            context_str += f"Model Thought: {h.thought_text}\n"
        if h.result_text:
            context_str += f"Model Output: {h.result_text}\n"
        context_str += "---------------------------------------------\n"
    
    # 保存されている音声ファイルの情報もコンテキストに含める
    user_prefix = f"user_{user_id}_"
    files_info = ""
    if os.path.exists(app.config['UPLOAD_FOLDER']):
        files = [f for f in os.listdir(app.config['UPLOAD_FOLDER']) if f.startswith(user_prefix)]
        if files:
            files_info = "\n--- SAVED DATA (AVAILABLE AUDIO FILES) ---\n"
            for f in sorted(files):
                filepath = os.path.join(app.config['UPLOAD_FOLDER'], f)
                try:
                    stats = os.stat(filepath)
                    dt = datetime.fromtimestamp(stats.st_mtime)
                    files_info += f"- Saved Audio: {f} (Uploaded: {dt.strftime('%H:%M:%S')})\n"
                except Exception:
                    continue
            files_info += "------------------------------------------\n"

    return context_str + files_info

def save_history(user_id, action_type, input_summary, thought, result):
    try:
        if not thought and not result:
            return
        # アプリケーションコンテキスト内で実行する必要があるため、呼び出し元で制御するか、
        # ここで create_app するかは構成による。
        # 今回は stream_with_context 内で current_app が使える前提。
        with app.app_context():
            new_h = History(
                user_id=user_id,
                action_type=action_type,
                input_summary=input_summary,
                thought_text=thought,
                result_text=result
            )
            db.session.add(new_h)
            db.session.commit()
    except Exception as e:
        logger.error(f"History save error: {e}")


# --- ルート / プロセッサ（分割モジュール。上の定義がすべて揃ってから最後に import する） ---
# 各モジュールは `from app import ...` で上の共有オブジェクトを、`core.<name>` でテストが差し替える
# 可変名（redis_client / create_task など）を参照する。詳細は app/README.md。
import prompts, streaming, processors, processors_openai  # noqa: E402,F401
import routes_auth, routes_words, routes_transcribe, routes_grok_live, routes_files, routes_tasks  # noqa: E402,F401

if __name__ == '__main__':
    with app.app_context():
        db.create_all()
    if not os.path.exists(app.config['UPLOAD_FOLDER']):
        os.makedirs(app.config['UPLOAD_FOLDER'])
    app.run(host='127.0.0.1', port=8003)
