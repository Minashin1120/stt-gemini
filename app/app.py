import os
import json
import requests
import base64
import time
import threading
import secrets
import uuid
import logging
import fcntl
from datetime import datetime, timedelta
from flask import Flask, render_template, request, redirect, url_for, flash, jsonify, Response, stream_with_context, session, send_from_directory
from flask_login import LoginManager, UserMixin, login_user, login_required, logout_user, current_user
from flask_sqlalchemy import SQLAlchemy
from cryptography.fernet import Fernet
from werkzeug.security import generate_password_hash, check_password_hash
from werkzeug.utils import secure_filename
from werkzeug.middleware.proxy_fix import ProxyFix
from dotenv import load_dotenv
from sqlalchemy import inspect
from sqlalchemy.exc import IntegrityError

load_dotenv()

# Setup logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s [%(levelname)s] %(message)s')
logger = logging.getLogger(__name__)

app = Flask(__name__)
app.wsgi_app = ProxyFix(app.wsgi_app, x_for=1, x_proto=1)
app.config['SECRET_KEY'] = os.getenv('SECRET_KEY')
app.config['SQLALCHEMY_DATABASE_URI'] = os.getenv('SQLALCHEMY_DATABASE_URI')
app.config['UPLOAD_FOLDER'] = os.path.join(app.root_path, 'uploads')
app.config['MAX_CONTENT_LENGTH'] = 100 * 1024 * 1024  # 100MBまで単一アップロード、超えると分割
app.config['PERMANENT_SESSION_LIFETIME'] = timedelta(days=1)
app.config['SESSION_COOKIE_SECURE'] = True
app.config['SESSION_COOKIE_HTTPONLY'] = True
app.config['SESSION_COOKIE_SAMESITE'] = 'Lax'
app.config['REMEMBER_COOKIE_SECURE'] = True
app.config['REMEMBER_COOKIE_HTTPONLY'] = True
app.config['REMEMBER_COOKIE_SAMESITE'] = 'Lax'
app.config['REMEMBER_COOKIE_DURATION'] = timedelta(days=1)

MAX_GEMINI_AUDIO_BYTES = 100 * 1024 * 1024
MAX_XAI_AUDIO_BYTES = 500 * 1024 * 1024
MAX_OPENAI_AUDIO_BYTES = 25 * 1024 * 1024  # 25MB
MAX_CHUNK_BYTES = 6 * 1024 * 1024
MAX_CHUNKS = 100
MAX_INCOMPLETE_UPLOADS = 3
MAX_API_KEY_LENGTH = 512
MAX_TEXT_LENGTH = 200_000
MAX_INSTRUCTION_LENGTH = 20_000
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
    'grok-stt',
    'gpt-transcribe',
    'gpt-live-transcribe',
}

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

def verify_turnstile(token):
    secret = os.getenv('TURNSTILE_SECRET_KEY')
    if not secret or not token:
        return False
    try:
        res = requests.post(
            'https://challenges.cloudflare.com/turnstile/v0/siteverify',
            data={'secret': secret, 'response': token},
            timeout=10
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

# --- Routes ---
@app.route('/favicon.ico')
def favicon(): return "", 204

@app.route('/welcome')
def welcome():
    if current_user.is_authenticated: return redirect(url_for('index'))
    return render_template('welcome.html')

@app.route('/')
def index():
    if not current_user.is_authenticated: return redirect(url_for('welcome'))
    return render_template('index.html')

@app.route('/register', methods=['GET', 'POST'])
def register():
    if request.method == 'POST':
        if not check_rate_limit('register', 3, 60):
            flash('試行回数が多すぎます。しばらく経ってから再度お試しください。')
            return redirect(url_for('register'))
        turnstile_token = request.form.get('cf-turnstile-response')
        if not verify_turnstile(turnstile_token):
            flash('ロボットではないことを証明してください。')
            return redirect(url_for('register'))

        # フォーム表示からの経過時間をチェック (ボットは極めて速いため)
        load_time = session.pop('_form_load_time', 0)
        elapsed = time.time() - load_time
        if elapsed < 0.5:
            logger.warning(f"Registration rejected: Too fast submission ({elapsed:.2f}s)")
            return "Access Denied: Unnatural submission speed.", 403

        if is_atypical_client(request):
            flash('不審なアクセスが検知されました。ブラウザの設定を確認してください。')
            return redirect(url_for('register'))
        
        username = (request.form.get('username') or '').strip()
        password = request.form.get('password')
        if len(username) < 2 or len(username) > 150:
            flash('ユーザー名は2〜150文字で入力してください。')
            return redirect(url_for('register'))
        if not password or len(password) < 8 or len(password) > 1024:
            flash('パスワードは8〜1024文字で入力してください。')
            return redirect(url_for('register'))
        if User.query.filter_by(username=username).first():
            flash('ユーザー名が重複しています。')
            return redirect(url_for('register'))
        new_user = User(username=username, password=generate_password_hash(password))
        db.session.add(new_user)
        try:
            db.session.commit()
        except IntegrityError:
            db.session.rollback()
            flash('ユーザー名が重複しています。')
            return redirect(url_for('register'))
        login_user(new_user, remember=True)
        return redirect(url_for('settings'))
    
    # フォーム表示時刻を記録
    session['_form_load_time'] = time.time()
    return render_template('register.html')

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

@app.route('/login', methods=['GET', 'POST'])
def login():
    if request.method == 'POST':
        if not check_rate_limit('login', 5, 60):
            flash('試行回数が多すぎます。しばらく経ってから再度お試しください。')
            return redirect(url_for('login'))
        turnstile_token = request.form.get('cf-turnstile-response')
        if not verify_turnstile(turnstile_token):
            flash('ロボットではないことを証明してください。')
            return redirect(url_for('login'))

        # フォーム表示からの経過時間をチェック
        load_time = session.pop('_form_load_time', 0)
        
        if time.time() - load_time < 0.5:
            return "Access Denied: Unnatural submission speed.", 403

        username = (request.form.get('username') or '').strip()
        password = request.form.get('password') or ''
        
        # 不審なクライアントチェック (ここでは拒否するが永続ロックはしない)
        if is_atypical_client(request):
            flash('不審なクライアントからのアクセスが検知されました。ブラウザの設定を確認してください。')
            return redirect(url_for('login'))

        if len(username) > 150 or len(password) > 1024:
            flash('ログイン失敗。')
            return redirect(url_for('login'))

        user = User.query.filter_by(username=username).first()

        # 定数時間比較: ユーザーが存在しない場合もダミーハッシュで比較し、タイミング差をなくす
        if user:
            password_valid = check_password_hash(user.password, password)
        else:
            # 存在しないユーザーの場合もダミーハッシュで比較 (時間差による列挙防止)
            dummy_hash = generate_password_hash('dummy_value_for_timing')
            check_password_hash(dummy_hash, password)
            password_valid = False
        
        if not password_valid:
            flash('ログイン失敗。')
        elif user.is_locked:
            flash('アカウントがロックされています。解除が必要な場合は下記から申請してください。')
            return redirect(url_for('login'))
        else:
            session.clear()
            login_user(user, remember=True)
            session['csrf_token'] = secrets.token_urlsafe(32)
            session.permanent = True
            return redirect(url_for('index'))
    
    # フォーム表示時刻を記録
    session['_form_load_time'] = time.time()
    return render_template('login.html')

@app.route('/request_unlock', methods=['GET', 'POST'])
def request_unlock():
    if request.method == 'POST':
        if not check_rate_limit('request_unlock', 3, 60):
            flash('試行回数が多すぎます。しばらく経ってから再度お試しください。')
            return redirect(url_for('request_unlock'))
        turnstile_token = request.form.get('cf-turnstile-response')
        if not verify_turnstile(turnstile_token):
            flash('ロボットではないことを証明してください。')
            return redirect(url_for('request_unlock'))

        if is_atypical_client(request):
            flash('不審な操作が検知されました。ブラウザの設定や拡張機能を確認してください。')
            return redirect(url_for('request_unlock'))

        username = request.form.get('username')
        user = User.query.filter_by(username=username).first()
        if user and user.is_locked and not user.unlock_requested:
            user.unlock_requested = True
            db.session.commit()
            notify_admin_unlock(username)
        flash('申請を受け付けました。ロック解除対象となる場合、管理者が対応します。')
        return redirect(url_for('login'))

    return render_template('request_unlock.html')

@app.route('/logout', methods=['POST'])
@login_required
def logout():
    session.clear()
    logout_user()
    return redirect(url_for('welcome'))

@app.route('/settings', methods=['GET', 'POST'])
@login_required
def settings():
    if request.method == 'POST':
        # 設定変更時のセキュリティチェック
        if is_atypical_client(request):
            flash('不審な操作が検知されました。ブラウザの設定や拡張機能を確認してください。')
            return redirect(url_for('settings'))

        api_key = request.form.get('api_key')
        xai_api_key = request.form.get('xai_api_key')
        openai_api_key = request.form.get('openai_api_key')
        retention_minutes = request.form.get('retention_minutes')
        
        if api_key and len(api_key) <= MAX_API_KEY_LENGTH:
            current_user.set_api_key(api_key)
            flash('Gemini APIキーを保存しました。')
        elif api_key:
            flash('Gemini APIキーが長すぎます。')
        
        if xai_api_key and len(xai_api_key) <= MAX_API_KEY_LENGTH:
            current_user.set_xai_api_key(xai_api_key)
            flash('xAI APIキーを保存しました。')
        elif xai_api_key:
            flash('xAI APIキーが長すぎます。')
        
        if openai_api_key and len(openai_api_key) <= MAX_API_KEY_LENGTH:
            current_user.set_openai_api_key(openai_api_key)
            flash('OpenAI APIキーを保存しました。')
        elif openai_api_key:
            flash('OpenAI APIキーが長すぎます。')
        
        if retention_minutes is not None:
            try:
                retention_value = int(retention_minutes)
                if retention_value < 1 or retention_value > 1440:
                    flash('保存期間は1〜1440分の範囲で指定してください。')
                else:
                    current_user.retention_minutes = retention_value
                    flash('保存期間の設定を更新しました。')
            except ValueError:
                flash('保存期間には数値を入力してください。')
        
        db.session.commit()
    return render_template(
        'settings.html',
        has_key=current_user.encrypted_api_key is not None,
        has_xai_key=current_user.encrypted_xai_api_key is not None,
        has_openai_key=current_user.encrypted_openai_api_key is not None,
    )

@app.route('/api/check_api_keys', methods=['GET'])
@login_required
def check_api_keys():
    return jsonify({
        'has_gemini_key': current_user.encrypted_api_key is not None,
        'has_xai_key': current_user.encrypted_xai_api_key is not None,
        'has_openai_key': current_user.encrypted_openai_api_key is not None,
    })

@app.route('/api/check_api_key', methods=['POST'])
@login_required
def check_api_key():
    data = request.get_json(silent=True) or {}
    model = data.get('model', '')
    if model in ('gpt-transcribe', 'gpt-live-transcribe'):
        has_key = current_user.encrypted_openai_api_key is not None
    elif model == 'grok-stt':
        has_key = current_user.encrypted_xai_api_key is not None
    else:
        has_key = current_user.encrypted_api_key is not None
    return jsonify({'has_key': has_key})

@app.route('/api/save_api_key', methods=['POST'])
@login_required
def save_api_key():
    data = request.get_json(silent=True) or {}
    key_type = data.get('type')
    api_key = data.get('api_key', '').strip()
    if not api_key:
        return jsonify({'error': 'APIキーを入力してください'}), 400
    if len(api_key) > MAX_API_KEY_LENGTH:
        return jsonify({'error': 'APIキーが長すぎます'}), 400
    if key_type == 'xai':
        current_user.set_xai_api_key(api_key)
        flash('xAI APIキーを保存しました。')
    elif key_type == 'gemini':
        current_user.set_api_key(api_key)
        flash('Gemini APIキーを保存しました。')
    elif key_type == 'openai':
        current_user.set_openai_api_key(api_key)
        flash('OpenAI APIキーを保存しました。')
    else:
        return jsonify({'error': 'APIキー種別が不正です'}), 400
    db.session.commit()
    return jsonify({'success': True})

@app.route('/api/delete_account', methods=['POST'])
@login_required
def delete_account():
    user_id = current_user.id
    try:
        # 1. 関連ファイルの削除
        user_prefix = f"user_{user_id}_"
        if os.path.exists(app.config['UPLOAD_FOLDER']):
            for f in os.listdir(app.config['UPLOAD_FOLDER']):
                if f.startswith(user_prefix):
                    try:
                        os.remove(os.path.join(app.config['UPLOAD_FOLDER'], f))
                    except OSError as file_error:
                        logger.warning(f"Account file removal failed: {file_error}")
        shutil.rmtree(get_user_chunks_root(user_id), ignore_errors=True)
        
        # 2. データベースレコードの削除 (History, WordSet, User)
        History.query.filter_by(user_id=user_id).delete()
        for word_set in WordSet.query.filter_by(user_id=user_id).all():
            db.session.delete(word_set)
        user = db.session.get(User, user_id)
        db.session.delete(user)
        db.session.commit()

        for task_id in redis_client.smembers(f"user:{user_id}:tasks") or set():
            delete_task(task_id)
        redis_client.delete(f"user:{user_id}:tasks", f"user:{user_id}:active_task")
        
        session.clear()
        logout_user()
        return jsonify({'success': True})
    except Exception as e:
        db.session.rollback()
        logger.error(f"Account deletion failed for user {current_user.username}: {e}", exc_info=True)
        return jsonify({'error': 'アカウント削除に失敗しました'}), 500

# --- Word List Routes ---
@app.route('/api/word_sets/manage_html')
@login_required
def word_sets_manage_html():
    sets = WordSet.query.filter_by(user_id=current_user.id).all()
    return render_template('partials/_word_sets.html', word_sets=sets)

@app.route('/api/word_sets')
@login_required
def list_word_sets():
    sets = WordSet.query.filter_by(user_id=current_user.id).all()
    return jsonify([{'id': s.id, 'name': s.name, 'is_active': s.is_active} for s in sets])

@app.route('/api/word_sets/create', methods=['POST'])
@login_required
def create_word_set():
    name = (request.form.get('name') or '新セット').strip()[:100]
    if not name:
        name = '新セット'
    new_set = WordSet(user_id=current_user.id, name=name)
    db.session.add(new_set)
    db.session.commit()
    return jsonify({'success': True, 'id': new_set.id})

@app.route('/api/word_sets/delete/<int:set_id>', methods=['POST'])
@login_required
def delete_word_set(set_id):
    ws = WordSet.query.filter_by(id=set_id, user_id=current_user.id).first()
    if ws:
        db.session.delete(ws)
        db.session.commit()
        return jsonify({'success': True})
    return jsonify({'error': 'Not found'}), 404

@app.route('/api/word_sets/toggle/<int:set_id>', methods=['POST'])
@login_required
def toggle_word_set(set_id):
    ws = WordSet.query.filter_by(id=set_id, user_id=current_user.id).first()
    if ws:
        ws.is_active = not ws.is_active
        db.session.commit()
        return jsonify({'success': True, 'is_active': ws.is_active})
    return jsonify({'error': 'Not found'}), 404

@app.route('/api/word_sets/reset', methods=['POST'])
@login_required
def reset_word_sets():
    WordSet.query.filter_by(user_id=current_user.id).update({WordSet.is_active: False})
    db.session.commit()
    return jsonify({'success': True})

@app.route('/api/words/add', methods=['POST'])
@login_required
def add_word():
    try:
        set_id = int(request.form.get('set_id'))
    except (TypeError, ValueError):
        return jsonify({'error': 'Invalid set'}), 400
    reading = (request.form.get('reading') or '').strip()[:255]
    replacement = (request.form.get('replacement') or '').strip()[:255]
    ws = WordSet.query.filter_by(id=set_id, user_id=current_user.id).first()
    if ws and reading and replacement:
        new_word = Word(set_id=ws.id, reading=reading, replacement=replacement)
        db.session.add(new_word)
        db.session.commit()
        return jsonify({'success': True, 'id': new_word.id})
    return jsonify({'error': 'Invalid data'}), 400

@app.route('/api/yomigana/generate', methods=['POST'])
@login_required
def generate_yomigana():
    word = (request.form.get('word') or '').strip()
    model = request.form.get('model', 'gemini-3.5-flash')
    if not word:
        return jsonify({'error': 'Word is required'}), 400
    model = validate_model(model)
    if model not in ALLOWED_MODELS or not model.startswith('gemini-'):
        model = 'gemini-3.5-flash'
    api_key = current_user.get_api_key()
    if not api_key:
        return jsonify({'error': 'Gemini API key not configured'}), 400
    prompt = f"次の単語の読み方をひらがな（スペースなし）で答えてください。読み方だけを出力し、他の文章は含めないでください。\n単語: {word}"
    try:
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
        response = requests.post(
            url,
            headers={'Content-Type': 'application/json', 'x-goog-api-key': api_key},
            json={'contents': [{'parts': [{'text': prompt}]}]},
            timeout=30
        )
        if response.status_code != 200:
            return jsonify({'error': f'API Error: {response.status_code}'}), 500
        data = response.json()
        reading = data['candidates'][0]['content']['parts'][0]['text'].strip()
        return jsonify({'reading': reading})
    except Exception as e:
        logger.error(f"Yomigana generation failed: {e}", exc_info=True)
        return jsonify({'error': '生成に失敗しました'}), 500

@app.route('/api/words/delete/<int:word_id>', methods=['POST'])
@login_required
def delete_word(word_id):
    w = Word.query.join(WordSet).filter(Word.id == word_id, WordSet.user_id == current_user.id).first()
    if w:
        db.session.delete(w)
        db.session.commit()
        return jsonify({'success': True})
    return jsonify({'error': 'Not found'}), 404

# --- Background Task Processor (writes progress to Redis) ---
def process_gemini_background(task_id, api_key, payload, user_id, action_type, input_summary, model="gemini-3.5-flash"):
    try:
        if task_is_cancelled(task_id):
            return
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse"
        
        full_thought = ""
        full_text = ""
        had_error = False
        
        max_retries = 3
        retry_delay = 2
        
        for attempt in range(max_retries + 1):
            try:
                if task_is_cancelled(task_id):
                    return
                update_task(task_id, phase='sending_to_api')
                response = requests.post(
                    url,
                    headers={'Content-Type': 'application/json', 'x-goog-api-key': api_key},
                    json=payload,
                    stream=True,
                    timeout=(10, 600),
                )
                
                if response.status_code == 429 and attempt < max_retries:
                    update_task(task_id, status='running')
                    for _ in range(retry_delay * 10):
                        if task_is_cancelled(task_id):
                            response.close()
                            return
                        time.sleep(0.1)
                    retry_delay *= 2
                    continue
                
                if response.status_code != 200:
                    had_error = True
                    if response.status_code == 429:
                        update_task(task_id, status='error', error='API Error 429: リクエスト制限に達しました。時間をおいて再度お試しください。')
                    else:
                        update_task(task_id, status='error', error=f'API Error {response.status_code}')
                    return

                update_task(task_id, phase='transcribing')
                for line in response.iter_lines():
                    if task_is_cancelled(task_id):
                        response.close()
                        return
                    if line:
                        decoded = line.decode('utf-8')
                        if decoded.startswith('data: '):
                            try:
                                data = json.loads(decoded[6:])
                                parts = data.get('candidates', [{}])[0].get('content', {}).get('parts', [])
                                for p in parts:
                                    if p.get('thought'):
                                        content = p.get('text', '')
                                        if not content:
                                            continue
                                        full_thought += content
                                        update_task(task_id, thought=full_thought, result=full_text)
                                    elif 'text' in p:
                                        content = p['text']
                                        full_text += content
                                        update_task(task_id, thought=full_thought, result=full_text)
                            except:
                                pass
                break
            except Exception as e:
                had_error = True
                logger.error(f"Gemini API request failed for task {task_id}: {e}", exc_info=True)
                update_task(task_id, status='error', error='APIリクエスト中にエラーが発生しました')
                return
        
        if had_error or task_is_cancelled(task_id):
            return
        
        save_history(user_id, action_type, input_summary, full_thought, full_text)
        update_task(task_id, status='done', thought=full_thought, result=full_text)
    except Exception as e:
        logger.error(f"Background task {task_id} failed: {e}", exc_info=True)
        update_task(task_id, status='error', error='処理中にエラーが発生しました')

# --- SSE Generator that polls Redis for task progress ---
# 各フェーズの表示メッセージ。バックグラウンド処理が update_task(phase=...) で切り替える。
PHASE_LABELS = {
    'sending_to_api': 'APIサーバーに送信中...',
    'transcribing': '解析中...',
}

def stream_task_updates(task_id):
    last_thought = ""
    last_text = ""
    last_phase = ""
    while True:
        task = get_task(task_id)
        if not task:
            yield f"data: {json.dumps({'type': 'error', 'content': 'Task not found'})}\n\n"
            return
        
        status = task.get('status', 'running')
        thought = task.get('thought', '') or ''
        text = task.get('result', '') or ''
        phase = task.get('phase', '') or ''
        
        if phase != last_phase and phase in PHASE_LABELS:
            yield f"data: {json.dumps({'type': 'status', 'content': PHASE_LABELS[phase]})}\n\n"
            last_phase = phase
        
        if thought != last_thought:
            new_part = thought[len(last_thought):]
            if new_part:
                yield f"data: {json.dumps({'type': 'thought', 'content': new_part})}\n\n"
            last_thought = thought
        
        if text != last_text:
            new_part = text[len(last_text):]
            if new_part:
                yield f"data: {json.dumps({'type': 'text', 'content': new_part})}\n\n"
            last_text = text
        
        if status == 'done':
            yield f"data: {json.dumps({'type': 'done'})}\n\n"
            return
        elif status == 'error':
            yield f"data: {json.dumps({'type': 'error', 'content': task.get('error', 'Unknown error')})}\n\n"
            return
        elif status == 'cancelled':
            yield f"data: {json.dumps({'type': 'cancelled', 'content': task.get('error', '処理を停止しました')})}\n\n"
            return
        
        time.sleep(0.3)

def create_stream_response(generator, task_id=None):
    response = Response(stream_with_context(generator), mimetype='text/event-stream')
    response.headers['Cache-Control'] = 'no-cache'
    response.headers['X-Accel-Buffering'] = 'no'
    if task_id:
        response.headers['X-Task-ID'] = task_id
    return response

# プロンプト定数
VERBATIM_INSTRUCTION = """
STRICT INSTRUCTION:
1. Transcribe the audio exactly as spoken (Verbatim).
2. Do NOT use your internal knowledge to correct facts, dates, or years.
3. Output ONLY the text. No preamble.
4. Do NOT insert line breaks in the middle of a sentence, even if there is a pause in the speech. Only use line breaks at the end of a complete sentence or when the speaker/topic changes significantly.
"""

REPHRASE_AWARE_INSTRUCTION = """
STRICT INSTRUCTION WITH REPHRASE CORRECTION:
1. When the speaker starts a phrase, then immediately corrects it, treat the corrected wording as the final text.
2. Omit abandoned false starts and mistaken fragments that were clearly superseded by the correction.
3. Still transcribe ordinary speech verbatim when there is no self-correction.
4. Do NOT add explanations or notes. Output ONLY the final text.
"""

# テキストのみの言い直し修正（音声・履歴を送らない後処理用）
TEXT_REPHRASE_CORRECTION_PROMPT = """You are correcting a speech transcription for self-corrections (rephrasing).

STRICT RULES:
1. When the text shows the speaker started a phrase then immediately corrected it, keep only the corrected wording as the final text.
2. Omit abandoned false starts and mistaken fragments that were clearly superseded by the correction.
3. If there is no self-correction pattern, leave the text unchanged.
4. Do NOT change wording for style, grammar "improvement", facts, or polish. Only remove superseded fragments.
5. Preserve line breaks and punctuation of the kept text as much as possible.
6. Do NOT add explanations, labels, or notes. Output ONLY the final corrected text.

Transcription text:
"""

FILLER_REMOVAL_RULE = """
Additionally, remove filler words, hesitations, and filled pauses such as "えーと", "あー", "うー", "んー", "えっと", "あのー", "そのー", "まあ", "えー", "あっ", "あの", "その", "ええと", "あのう", "そのう", and similar non-lexical vocalizations from the transcription. Transcribe the remaining substantive speech naturally and coherently, minimizing any impact on the substantive content.
"""

LITE_OUTPUT_CORRECTION = """
Additionally, for this transcription:
1. Do NOT insert unnatural spaces in the Japanese text.
2. If the entire output lacks punctuation marks (such as "。" or "、"), add appropriate punctuation to improve readability. If any punctuation is already present, leave punctuation unchanged.
"""

def is_truthy(value):
    return str(value).strip().lower() in {"1", "true", "yes", "on"}

def build_transcription_prompt(history_context, word_list_context, mode_label, allow_rephrase_correction=False, allow_filler_removal=False, is_lite_model=False):
    prompt = f"{history_context}\n{word_list_context}\n"
    if allow_rephrase_correction:
        base_instruction = REPHRASE_AWARE_INSTRUCTION
    else:
        base_instruction = VERBATIM_INSTRUCTION
    if allow_filler_removal:
        base_instruction += FILLER_REMOVAL_RULE
    if is_lite_model:
        base_instruction += LITE_OUTPUT_CORRECTION
    if allow_rephrase_correction:
        prompt += f"MODE: {mode_label}\nTASK: {base_instruction}"
    else:
        prompt += f"TASK: {base_instruction}"
    return prompt

@app.route('/transcribe', methods=['POST'])
@login_required
def transcribe():
    reject_if_active_task()
    if not check_user_model_rate_limit():
        return jsonify({'error': '処理回数が上限を超えました。時間をおいて再度お試しください。'}), 429
    model = validate_model(request.form.get('model', 'gemini-3.5-flash'))
    
    file = request.files.get('audio_file')
    if not file: return jsonify({'error': 'No file'}), 400

    # 受信ファイル名は必ず安全化する。拡張子は許可済みのものだけ使う。
    ext, mime_type = get_audio_metadata(file.filename, file.mimetype)
    if not ext:
        return jsonify({'error': '対応していない音声形式です'}), 400
    
    # 新規録音の場合、以前の履歴と音声ファイルをクリアしてから処理する
    if not is_truthy(request.form.get('is_append')):
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

    if model in ('gpt-transcribe', 'gpt-live-transcribe'):
        api_key = current_user.get_openai_api_key()
        if not api_key: return jsonify({'error': 'OpenAI API Key not set. Go to Settings to configure it.'}), 400
        
        task_id = create_task(current_user.id, "transcribe", "Audio Input", model)
        if model == 'gpt-transcribe':
            target = process_openai_gpt_transcribe_background
            args = (task_id, api_key, filepath, current_user.id, "transcribe", "Audio Input")
        else:
            target = process_openai_gpt_live_transcribe_background
            args = (task_id, api_key, filepath, current_user.id, "transcribe", "Audio Input")
        thread = threading.Thread(target=target, args=args)
        thread.daemon = True
        thread.start()
        return create_stream_response(stream_task_updates(task_id), task_id)
    
    if model == 'grok-stt':
        api_key = current_user.get_xai_api_key()
        if not api_key: return jsonify({'error': 'xAI API Key not set. Go to Settings to configure it.'}), 400
        
        task_id = create_task(current_user.id, "transcribe", "Audio Input", model)
        thread = threading.Thread(
            target=process_grok_stt_background,
            args=(task_id, api_key, filepath, current_user.id, "transcribe", "Audio Input")
        )
        thread.daemon = True
        thread.start()
        return create_stream_response(stream_task_updates(task_id), task_id)
    
    # Gemini path
    api_key = current_user.get_api_key()
    if not api_key: return jsonify({'error': 'API Key not set'}), 400
    
    with open(filepath, "rb") as f:
        audio_b64 = base64.b64encode(f.read()).decode('utf-8')
    
    # 履歴コンテキスト取得
    history_context = get_active_history_context(current_user.id)
    word_list_context = get_word_list_context(current_user.id)
    
    allow_rephrase_correction = is_truthy(request.form.get('allow_rephrase_correction'))
    allow_filler_removal = is_truthy(request.form.get('allow_filler_removal'))
    is_lite = model in ('gemini-3.5-flash-lite', 'gemini-3.1-flash-lite')
    full_prompt = build_transcription_prompt(
        history_context,
        word_list_context,
        "The user enabled rephrase correction mode for this transcription.",
        allow_rephrase_correction=allow_rephrase_correction,
        allow_filler_removal=allow_filler_removal,
        is_lite_model=is_lite,
    )
    
    payload = {
        "contents": [{"parts": [
            {"text": full_prompt},
            {"inline_data": {"mime_type": mime_type, "data": audio_b64}}
        ]}],
        "generationConfig": {"thinkingConfig": {"includeThoughts": True, "thinkingLevel": get_thinking_level(request.form.get('thinking_level'))}}
    }
    
    task_id = create_task(current_user.id, "transcribe", "Audio Input", model)
    thread = threading.Thread(
        target=process_gemini_background,
        args=(task_id, api_key, payload, current_user.id, "transcribe", "Audio Input", model)
    )
    thread.daemon = True
    thread.start()
    return create_stream_response(stream_task_updates(task_id), task_id)

@app.route('/reanalyze', methods=['POST'])
@login_required
def reanalyze():
    reject_if_active_task()
    if not check_user_model_rate_limit():
        return jsonify({'error': '処理回数が上限を超えました。時間をおいて再度お試しください。'}), 429
    data = request.get_json(silent=True) or {}
    model = validate_model(data.get('model', 'gemini-3.5-flash'))
    
    filename = session.get('last_audio_file')
    if not filename: return jsonify({'error': 'ファイルなし'}), 400
    filepath = resolve_user_upload_path(filename, current_user.id)
    if not filepath or not os.path.exists(filepath): return jsonify({'error': '期限切れ'}), 400

    if model in ('gpt-transcribe', 'gpt-live-transcribe'):
        api_key = current_user.get_openai_api_key()
        if not api_key: return jsonify({'error': 'OpenAI API Key not set'}), 400
        
        task_id = create_task(current_user.id, "reanalyze", "Re-analysis Request", model)
        if model == 'gpt-transcribe':
            target = process_openai_gpt_transcribe_background
            args = (task_id, api_key, filepath, current_user.id, "reanalyze", "Re-analysis Request")
        else:
            target = process_openai_gpt_live_transcribe_background
            args = (task_id, api_key, filepath, current_user.id, "reanalyze", "Re-analysis Request")
        thread = threading.Thread(target=target, args=args)
        thread.daemon = True
        thread.start()
        return create_stream_response(stream_task_updates(task_id), task_id)
    
    if model == 'grok-stt':
        api_key = current_user.get_xai_api_key()
        if not api_key: return jsonify({'error': 'xAI API Key not set'}), 400
        
        task_id = create_task(current_user.id, "reanalyze", "Re-analysis Request", model)
        thread = threading.Thread(
            target=process_grok_stt_background,
            args=(task_id, api_key, filepath, current_user.id, "reanalyze", "Re-analysis Request")
        )
        thread.daemon = True
        thread.start()
        return create_stream_response(stream_task_updates(task_id), task_id)
    
    api_key = current_user.get_api_key()
    if not api_key: return jsonify({'error': 'API Key not set'}), 400
    
    with open(filepath, "rb") as f:
        audio_b64 = base64.b64encode(f.read()).decode('utf-8')
    
    history_context = get_active_history_context(current_user.id)
    word_list_context = get_word_list_context(current_user.id)
    allow_rephrase_correction = is_truthy(data.get('allow_rephrase_correction'))
    allow_filler_removal = is_truthy(data.get('allow_filler_removal'))
    is_lite = model in ('gemini-3.5-flash-lite', 'gemini-3.1-flash-lite')
    if allow_rephrase_correction:
        base_instruction = REPHRASE_AWARE_INSTRUCTION
        mode_line = "MODE: The user enabled rephrase correction mode for this re-analysis."
    else:
        base_instruction = "Listen again carefully and transcribe exactly.\nDo NOT insert line breaks in the middle of a sentence, even if there is a pause in the speech."
        mode_line = ""
    if allow_filler_removal:
        base_instruction += FILLER_REMOVAL_RULE
    if is_lite:
        base_instruction += LITE_OUTPUT_CORRECTION
    prompt_parts = [history_context, word_list_context]
    if mode_line:
        prompt_parts.append(mode_line)
    prompt_parts.append("TASK: " + base_instruction)
    prompt = "\n".join(prompt_parts)
    
    payload = {
        "contents": [{"parts": [
            {"text": prompt},
            {"inline_data": {"mime_type": session.get('last_audio_mime') or 'audio/mpeg', "data": audio_b64}}
        ]}],
        "generationConfig": {"thinkingConfig": {"includeThoughts": True, "thinkingLevel": get_thinking_level(data.get('thinking_level'))}}
    }
    task_id = create_task(current_user.id, "reanalyze", "Re-analysis Request", model)
    thread = threading.Thread(
        target=process_gemini_background,
        args=(task_id, api_key, payload, current_user.id, "reanalyze", "Re-analysis Request", model)
    )
    thread.daemon = True
    thread.start()
    return create_stream_response(stream_task_updates(task_id), task_id)

@app.route('/improve', methods=['POST'])
@login_required
def improve():
    reject_if_active_task()
    if not check_user_model_rate_limit():
        return jsonify({'error': '処理回数が上限を超えました。時間をおいて再度お試しください。'}), 429
    api_key, data = current_user.get_api_key(), request.get_json(silent=True) or {}
    if not api_key:
        return jsonify({'error': 'API Key not set'}), 400
    text = data.get('text') or ''
    instruction = data.get('instruction') or ''
    if not text or not instruction:
        return jsonify({'error': 'テキストと指示を入力してください'}), 400
    if len(text) > MAX_TEXT_LENGTH or len(instruction) > MAX_INSTRUCTION_LENGTH:
        return jsonify({'error': '入力が長すぎます'}), 413
    use_audio = data.get('use_audio', False)
    
    # 手動修正を最新の履歴に反映（コンテキスト整合性のため）
    last_h = History.query.filter_by(user_id=current_user.id).order_by(History.timestamp.desc()).first()
    if last_h and text:
        last_h.result_text = text
        db.session.commit()
    
    history_context = get_active_history_context(current_user.id)
    word_list_context = get_word_list_context(current_user.id)
    
    parts = []
    # プロンプトを強化して手動修正を重視させる
    prompt = f"""
    {history_context}
    {word_list_context}
    
    IMPORTANT: The text in "Current Text" is the result of manual corrections by the user. 
    You MUST prioritize this "Current Text" as the definitive source for improvement, 
    even if it differs from the earlier transcription in the history.

    Current Text: {text}
    User Instruction: {instruction}
    Task: Refine or transform the "Current Text" according to the "User Instruction". Output ONLY the final improved result.
    """
    parts.append({"text": prompt})

    if use_audio:
        filename = session.get('last_audio_file')
        if filename:
            filepath = resolve_user_upload_path(filename, current_user.id)
            if filepath and os.path.exists(filepath):
                with open(filepath, "rb") as f:
                    audio_b64 = base64.b64encode(f.read()).decode('utf-8')
                parts.append({"text": "Reference Audio:"})
                parts.append({"inline_data": {"mime_type": session.get('last_audio_mime', 'audio/mp3'), "data": audio_b64}})

    payload = {
        "contents": [{"parts": parts}],
        "generationConfig": {"thinkingConfig": {"includeThoughts": True, "thinkingLevel": get_thinking_level(data.get('thinking_level'))}}
    }
    model = validate_model(data.get('model', 'gemini-3.5-flash'))
    if model in ('grok-stt', 'gpt-transcribe', 'gpt-live-transcribe'):
        model = 'gemini-3.5-flash'  # Grok/OpenAI STT cannot do text improvement
    task_id = create_task(current_user.id, "improve", instruction, model)
    thread = threading.Thread(
        target=process_gemini_background,
        args=(task_id, api_key, payload, current_user.id, "improve", instruction, model)
    )
    thread.daemon = True
    thread.start()
    return create_stream_response(stream_task_updates(task_id), task_id)

@app.route('/correct_rephrase', methods=['POST'])
@login_required
def correct_rephrase():
    """テキストのみの言い直し修正。音声・履歴・単語リストは送らない。"""
    reject_if_active_task()
    if not check_user_model_rate_limit():
        return jsonify({'error': '処理回数が上限を超えました。時間をおいて再度お試しください。'}), 429
    api_key, data = current_user.get_api_key(), request.get_json(silent=True) or {}
    if not api_key:
        return jsonify({'error': 'API Key not set'}), 400
    text = data.get('text') or ''
    if not text:
        return jsonify({'error': 'テキストを入力してください'}), 400
    if len(text) > MAX_TEXT_LENGTH:
        return jsonify({'error': '入力が長すぎます'}), 413

    # 音声・履歴・単語リストを一切含めない（Flash-Lite で音声経路の言い直しが弱いための後処理）
    prompt = TEXT_REPHRASE_CORRECTION_PROMPT + text
    payload = {
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {"thinkingConfig": {"includeThoughts": True, "thinkingLevel": get_thinking_level(data.get('thinking_level'))}}
    }
    model = validate_model(data.get('model', 'gemini-3.5-flash'))
    if model in ('grok-stt', 'gpt-transcribe', 'gpt-live-transcribe'):
        model = 'gemini-3.5-flash'  # Grok/OpenAI STT cannot do text correction
    summary = "Rephrase correction (text only)"
    task_id = create_task(current_user.id, "correct_rephrase", summary, model)
    thread = threading.Thread(
        target=process_gemini_background,
        args=(task_id, api_key, payload, current_user.id, "correct_rephrase", summary, model)
    )
    thread.daemon = True
    thread.start()
    return create_stream_response(stream_task_updates(task_id), task_id)

# --- File & History APIs ---
@app.route('/delete_audio', methods=['POST'])
@login_required
def delete_audio():
    fn = session.get('last_audio_file')
    if fn:
        p = resolve_user_upload_path(fn, current_user.id)
        if not p:
            return jsonify({'error': '権限なし'}), 403
        if os.path.exists(p):
            try:
                os.remove(p)
            except FileNotFoundError:
                pass
            except Exception as e:
                logger.error(f"delete_audio error: {e}")
                return jsonify({'error': '削除に失敗しました'}), 500
        session.pop('last_audio_file', None)
        session.pop('last_audio_mime', None)
        return jsonify({'success': True})
    return jsonify({'error': 'なし'}), 404

@app.route('/api/files')
@login_required
def list_files():
    files = []
    try:
        user_prefix = f"user_{current_user.id}_"
        if os.path.exists(app.config['UPLOAD_FOLDER']):
            for f in os.listdir(app.config['UPLOAD_FOLDER']):
                if f.startswith(user_prefix):
                    filepath = os.path.join(app.config['UPLOAD_FOLDER'], f)
                    try:
                        stats = os.stat(filepath)
                        dt = datetime.fromtimestamp(stats.st_mtime)
                        files.append({'filename': f, 'display_name': dt.strftime('%Y/%m/%d %H:%M:%S'), 'url': url_for('uploaded_file', filename=f), 'size': stats.st_size})
                    except Exception:
                        continue
        files.sort(key=lambda x: x['display_name'], reverse=True)
    except: pass
    return jsonify(files)

@app.route('/uploads/<filename>')
@login_required
def uploaded_file(filename):
    p = resolve_user_upload_path(filename, current_user.id)
    if not p or not os.path.exists(p): return "Access denied", 403
    return send_from_directory(app.config['UPLOAD_FOLDER'], os.path.basename(p))

def sanitize_upload_id(upload_id):
    if not upload_id or not isinstance(upload_id, str):
        return None
    sanitized = secure_filename(upload_id)
    if sanitized != upload_id or not sanitized:
        return None
    return sanitized

def parse_bounded_int(value, minimum, maximum):
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return None
    return parsed if minimum <= parsed <= maximum else None

def get_user_chunks_root(user_id):
    return os.path.join(app.config['UPLOAD_FOLDER'], '_chunks', f'user_{user_id}')

def get_chunks_usage(root):
    total = 0
    if not os.path.isdir(root):
        return total
    for dirpath, _, filenames in os.walk(root):
        for name in filenames:
            if not (name.startswith('chunk_') and name[6:].isdigit()):
                continue
            try:
                total += os.path.getsize(os.path.join(dirpath, name))
            except OSError:
                continue
    return total

def ensure_chunk_upload(upload_id, total_chunks, original_filename, user_id):
    ext, _ = get_audio_metadata(original_filename)
    if not ext:
        return None, '対応していない音声形式です'

    user_root = get_user_chunks_root(user_id)
    os.makedirs(user_root, mode=0o700, exist_ok=True)
    chunks_dir = os.path.join(user_root, upload_id)
    with open(os.path.join(user_root, '.lock'), 'a+', encoding='utf-8') as user_lock:
        fcntl.flock(user_lock, fcntl.LOCK_EX)
        if not os.path.isdir(chunks_dir):
            active = sum(os.path.isdir(os.path.join(user_root, name)) for name in os.listdir(user_root))
            if active >= MAX_INCOMPLETE_UPLOADS:
                return None, '同時アップロード数が上限に達しています'
            os.makedirs(chunks_dir, mode=0o700, exist_ok=True)

    metadata_path = os.path.join(chunks_dir, 'metadata.json')
    expected = {'total_chunks': total_chunks, 'extension': ext}
    with open(os.path.join(chunks_dir, '.lock'), 'a+', encoding='utf-8') as upload_lock:
        fcntl.flock(upload_lock, fcntl.LOCK_EX)
        if not os.path.exists(metadata_path):
            with open(metadata_path, 'w', encoding='utf-8') as metadata_file:
                json.dump(expected, metadata_file)
        else:
            try:
                with open(metadata_path, encoding='utf-8') as metadata_file:
                    if json.load(metadata_file) != expected:
                        return None, 'アップロード情報が一致しません'
            except (OSError, ValueError, TypeError):
                return None, 'アップロード情報が破損しています'
    return chunks_dir, None

@app.route('/api/upload_chunk', methods=['POST'])
@login_required
def upload_chunk():
    upload_id = request.form.get('upload_id')
    chunk_index = request.form.get('chunk_index')
    total_chunks = request.form.get('total_chunks')
    original_filename = request.form.get('original_filename') or ''
    chunk_file = request.files.get('chunk')

    upload_id = sanitize_upload_id(upload_id)
    if not upload_id:
        return jsonify({'error': 'Invalid upload_id'}), 400

    if not all([chunk_index, total_chunks, chunk_file]):
        return jsonify({'error': 'Missing fields'}), 400

    total_chunks = parse_bounded_int(total_chunks, 1, MAX_CHUNKS)
    if total_chunks is None:
        return jsonify({'error': 'Invalid total_chunks'}), 400
    chunk_index = parse_bounded_int(chunk_index, 0, total_chunks - 1)
    if chunk_index is None:
        return jsonify({'error': 'Invalid chunk_index'}), 400
    if not check_rate_limit(f'upload_chunk:user:{current_user.id}', 240, 60):
        return jsonify({'error': 'アップロード頻度が上限を超えました'}), 429

    chunks_dir, error = ensure_chunk_upload(
        upload_id, total_chunks, original_filename, current_user.id
    )
    if error:
        return jsonify({'error': error}), 400

    chunk_path = os.path.join(chunks_dir, f'chunk_{chunk_index:06d}')
    temp_path = f"{chunk_path}.{secrets.token_hex(4)}.tmp"
    try:
        chunk_file.save(temp_path)
        chunk_size = os.path.getsize(temp_path)
        if chunk_size <= 0 or chunk_size > MAX_CHUNK_BYTES:
            return jsonify({'error': 'Invalid chunk size'}), 413
        user_root = get_user_chunks_root(current_user.id)
        with open(os.path.join(user_root, '.lock'), 'a+', encoding='utf-8') as user_lock:
            fcntl.flock(user_lock, fcntl.LOCK_EX)
            with open(os.path.join(chunks_dir, '.lock'), 'a+', encoding='utf-8') as upload_lock:
                fcntl.flock(upload_lock, fcntl.LOCK_EX)
                if os.path.exists(os.path.join(chunks_dir, '.complete')):
                    return jsonify({'error': 'アップロードは結合処理中です'}), 409
                old_size = os.path.getsize(chunk_path) if os.path.exists(chunk_path) else 0
                projected_usage = get_chunks_usage(user_root) - old_size + chunk_size
                if projected_usage > MAX_XAI_AUDIO_BYTES:
                    return jsonify({'error': 'アップロード容量が上限を超えました'}), 413
                os.replace(temp_path, chunk_path)
    finally:
        try:
            if os.path.exists(temp_path):
                os.remove(temp_path)
        except OSError:
            pass

    return jsonify({'success': True, 'chunk_index': chunk_index})

@app.route('/api/upload_cancel', methods=['POST'])
@login_required
def upload_cancel():
    data = request.get_json(silent=True) or {}
    upload_id = request.form.get('upload_id') or data.get('upload_id')
    upload_id = sanitize_upload_id(upload_id)
    if not upload_id:
        return jsonify({'error': 'Invalid upload_id'}), 400
    chunks_dir = os.path.join(get_user_chunks_root(current_user.id), upload_id)
    if os.path.isdir(chunks_dir):
        # 結合処理中(.complete)のディレクトリは削除しない
        if not os.path.exists(os.path.join(chunks_dir, '.complete')):
            shutil.rmtree(chunks_dir, ignore_errors=True)
    return jsonify({'success': True})

@app.route('/api/upload_complete', methods=['POST'])
@login_required
def upload_complete():
    reject_if_active_task()
    if not check_user_model_rate_limit():
        return jsonify({'error': '処理回数が上限を超えました。時間をおいて再度お試しください。'}), 429
    model = validate_model(request.form.get('model', 'gemini-3.5-flash'))

    upload_id = request.form.get('upload_id')
    total_chunks = request.form.get('total_chunks')
    original_filename = request.form.get('original_filename') or ''

    upload_id = sanitize_upload_id(upload_id)
    if not upload_id:
        return jsonify({'error': 'Invalid upload_id'}), 400

    if not total_chunks:
        return jsonify({'error': 'Missing fields'}), 400

    total_chunks = parse_bounded_int(total_chunks, 1, MAX_CHUNKS)
    if total_chunks is None:
        return jsonify({'error': 'Invalid total_chunks'}), 400
    chunks_dir, error = ensure_chunk_upload(
        upload_id, total_chunks, original_filename, current_user.id
    )
    if error:
        return jsonify({'error': error}), 400

    # Verify all chunks present
    for i in range(total_chunks):
        chunk_path = os.path.join(chunks_dir, f'chunk_{i:06d}')
        if not os.path.exists(chunk_path):
            shutil.rmtree(chunks_dir, ignore_errors=True)
            return jsonify({'error': f'Missing chunk {i}'}), 400

    with open(os.path.join(chunks_dir, '.lock'), 'a+', encoding='utf-8') as upload_lock:
        fcntl.flock(upload_lock, fcntl.LOCK_EX)
        complete_marker = os.path.join(chunks_dir, '.complete')
        try:
            with open(complete_marker, 'x', encoding='utf-8'):
                pass
        except FileExistsError:
            return jsonify({'error': 'アップロードは結合処理中です'}), 409

    ext, mime_type = get_audio_metadata(original_filename)
    if model in ('gpt-transcribe', 'gpt-live-transcribe'):
        max_audio_bytes = MAX_OPENAI_AUDIO_BYTES
    elif model == 'grok-stt':
        max_audio_bytes = MAX_XAI_AUDIO_BYTES
    else:
        max_audio_bytes = MAX_GEMINI_AUDIO_BYTES
    total_size = 0
    for i in range(total_chunks):
        chunk_path = os.path.join(chunks_dir, f'chunk_{i:06d}')
        chunk_size = os.path.getsize(chunk_path)
        if chunk_size <= 0 or chunk_size > MAX_CHUNK_BYTES:
            shutil.rmtree(chunks_dir, ignore_errors=True)
            return jsonify({'error': 'Invalid chunk size'}), 413
        total_size += chunk_size
        if total_size > max_audio_bytes:
            shutil.rmtree(chunks_dir, ignore_errors=True)
            return jsonify({'error': '音声ファイルが上限サイズを超えています'}), 413

    filename = generate_audio_filename(current_user.id, ext)
    if not os.path.exists(app.config['UPLOAD_FOLDER']):
        os.makedirs(app.config['UPLOAD_FOLDER'])

    filepath = os.path.join(app.config['UPLOAD_FOLDER'], filename)

    # Merge chunks
    try:
        with open(filepath, 'wb') as outfile:
            for i in range(total_chunks):
                chunk_path = os.path.join(chunks_dir, f'chunk_{i:06d}')
                with open(chunk_path, 'rb') as infile:
                    shutil.copyfileobj(infile, outfile, length=1024 * 1024)
    except Exception as e:
        shutil.rmtree(chunks_dir, ignore_errors=True)
        if os.path.exists(filepath):
            os.remove(filepath)
        logger.error(f"Chunk merge failed for upload {upload_id}: {e}", exc_info=True)
        return jsonify({'error': 'ファイルの結合に失敗しました'}), 500

    shutil.rmtree(chunks_dir, ignore_errors=True)

    # 新規録音の場合、以前の履歴と音声ファイルをクリアする（今マージした自ファイルは残す）
    if not is_truthy(request.form.get('is_append')):
        History.query.filter_by(user_id=current_user.id).delete()
        db.session.commit()
        user_prefix = f"user_{current_user.id}_"
        if os.path.exists(app.config['UPLOAD_FOLDER']):
            for f in os.listdir(app.config['UPLOAD_FOLDER']):
                if f.startswith(user_prefix) and f != filename:
                    try:
                        os.remove(os.path.join(app.config['UPLOAD_FOLDER'], f))
                    except Exception as e:
                        logger.warning(f"clear_on_new file removal error: {e}")
        session.pop('last_audio_file', None)
        session.pop('last_audio_mime', None)

    session['last_audio_file'] = filename
    session['last_audio_mime'] = mime_type

    if model in ('gpt-transcribe', 'gpt-live-transcribe'):
        api_key = current_user.get_openai_api_key()
        if not api_key:
            return jsonify({'error': 'OpenAI API Key not set. Go to Settings to configure it.'}), 400

        task_id = create_task(current_user.id, "transcribe", "Audio Input", model)
        if model == 'gpt-transcribe':
            target = process_openai_gpt_transcribe_background
            args = (task_id, api_key, filepath, current_user.id, "transcribe", "Audio Input")
        else:
            target = process_openai_gpt_live_transcribe_background
            args = (task_id, api_key, filepath, current_user.id, "transcribe", "Audio Input")
        thread = threading.Thread(target=target, args=args)
        thread.daemon = True
        thread.start()
        return create_stream_response(stream_task_updates(task_id), task_id)

    if model == 'grok-stt':
        api_key = current_user.get_xai_api_key()
        if not api_key:
            return jsonify({'error': 'xAI API Key not set. Go to Settings to configure it.'}), 400

        task_id = create_task(current_user.id, "transcribe", "Audio Input", model)
        thread = threading.Thread(
            target=process_grok_stt_background,
            args=(task_id, api_key, filepath, current_user.id, "transcribe", "Audio Input")
        )
        thread.daemon = True
        thread.start()
        return create_stream_response(stream_task_updates(task_id), task_id)

    api_key = current_user.get_api_key()
    if not api_key:
        return jsonify({'error': 'API Key not set'}), 400

    with open(filepath, "rb") as f:
        audio_b64 = base64.b64encode(f.read()).decode('utf-8')

    history_context = get_active_history_context(current_user.id)
    word_list_context = get_word_list_context(current_user.id)

    allow_rephrase_correction = is_truthy(request.form.get('allow_rephrase_correction'))
    allow_filler_removal = is_truthy(request.form.get('allow_filler_removal'))
    is_lite = model in ('gemini-3.5-flash-lite', 'gemini-3.1-flash-lite')
    full_prompt = build_transcription_prompt(
        history_context, word_list_context,
        "The user enabled rephrase correction mode for this transcription.",
        allow_rephrase_correction=allow_rephrase_correction,
        allow_filler_removal=allow_filler_removal,
        is_lite_model=is_lite,
    )

    payload = {
        "contents": [{"parts": [
            {"text": full_prompt},
            {"inline_data": {"mime_type": mime_type, "data": audio_b64}}
        ]}],
        "generationConfig": {"thinkingConfig": {"includeThoughts": True, "thinkingLevel": get_thinking_level(request.form.get('thinking_level'))}}
    }

    task_id = create_task(current_user.id, "transcribe", "Audio Input", model)
    thread = threading.Thread(
        target=process_gemini_background,
        args=(task_id, api_key, payload, current_user.id, "transcribe", "Audio Input", model)
    )
    thread.daemon = True
    thread.start()
    return create_stream_response(stream_task_updates(task_id), task_id)

@app.route('/api/delete_file/<filename>', methods=['POST'])
@login_required
def delete_specific_file(filename):
    p = resolve_user_upload_path(filename, current_user.id)
    if not p: return jsonify({'error': '権限なし'}), 403
    if os.path.exists(p):
        try:
            os.remove(p)
        except FileNotFoundError:
            pass
        except Exception as e:
            logger.error(f"delete_specific_file error: {e}")
            return jsonify({'error': '削除に失敗しました'}), 500
        if session.get('last_audio_file') == filename:
            session.pop('last_audio_file', None)
            session.pop('last_audio_mime', None)
        return jsonify({'success': True})
    return jsonify({'error': 'なし'}), 404

@app.route('/api/delete_history/<int:history_id>', methods=['POST'])
@login_required
def delete_history(history_id):
    try:
        h = History.query.filter_by(id=history_id, user_id=current_user.id).first()
        if h:
            db.session.delete(h)
            db.session.commit()
            return jsonify({'success': True})
        return jsonify({'error': 'なし'}), 404
    except Exception as e:
        db.session.rollback()
        logger.error(f"History deletion failed for user {current_user.username}: {e}", exc_info=True)
        return jsonify({'error': '履歴の削除に失敗しました'}), 500

@app.route('/api/clear_history', methods=['POST'])
@login_required
def clear_history():
    try:
        History.query.filter_by(user_id=current_user.id).delete()
        db.session.commit()
        return jsonify({'success': True})
    except Exception as e:
        db.session.rollback()
        logger.error(f"History clear failed for user {current_user.username}: {e}", exc_info=True)
        return jsonify({'error': '履歴のクリアに失敗しました'}), 500

@app.route('/api/clear_all', methods=['POST'])
@login_required
def clear_all():
    try:
        # 1. 履歴を削除
        History.query.filter_by(user_id=current_user.id).delete()
        db.session.commit()
        
        # 2. ファイルを削除
        user_prefix = f"user_{current_user.id}_"
        if os.path.exists(app.config['UPLOAD_FOLDER']):
            for f in os.listdir(app.config['UPLOAD_FOLDER']):
                if f.startswith(user_prefix):
                    try:
                        os.remove(os.path.join(app.config['UPLOAD_FOLDER'], f))
                    except Exception as e:
                        logger.warning(f"clear_all file removal error: {e}")
                        continue
        
        # 3. セッション変数をクリア
        session.pop('last_audio_file', None)
        session.pop('last_audio_mime', None)
        
        return jsonify({'success': True})
    except Exception as e:
        db.session.rollback()
        logger.error(f"Clear all failed for user {current_user.username}: {e}", exc_info=True)
        return jsonify({'error': 'データのクリアに失敗しました'}), 500

@app.route('/api/history')
@login_required
def get_history():
    # ユーザー設定の保持時間内の履歴を返す
    limit_dt = datetime.utcnow() - timedelta(minutes=current_user.retention_minutes)
    histories = History.query.filter_by(user_id=current_user.id).filter(History.timestamp > limit_dt).order_by(History.timestamp.desc()).all()
    
    data = []
    now = datetime.utcnow()
    for h in histories:
        # 保持時間を過ぎているかどうかのフラグ (基本的にはフィルタリングされているので常にFalse)
        is_expired = (now - h.timestamp).total_seconds() > (current_user.retention_minutes * 60)
        data.append({
            'id': h.id,
            'action': h.action_type,
            'input': h.input_summary,
            'thought': h.thought_text,
            'result': h.result_text,
            'time': h.timestamp.strftime('%H:%M:%S'),
            'expired': is_expired
        })
    return jsonify(data)

# --- OpenAI Background Processors ---
def process_openai_gpt_transcribe_background(task_id, api_key, audio_filepath, user_id, action_type, input_summary, prompt="", keywords=None, languages=None, stream=True):
    try:
        if task_is_cancelled(task_id):
            return
        url = "https://api.openai.com/v1/audio/transcriptions"

        with open(audio_filepath, 'rb') as f:
            _, ext = os.path.splitext(audio_filepath)
            filename = os.path.basename(audio_filepath)

            files = {'file': (filename, f, 'application/octet-stream')}
            data = {'model': 'gpt-transcribe'}
            if stream:
                data['stream'] = 'true'
            if prompt:
                data['prompt'] = prompt
            if keywords:
                for kw in keywords:
                    data.setdefault('keywords[]', []).append(kw)
            if languages:
                for lang in languages:
                    data.setdefault('languages[]', []).append(lang)

            # requests doesn't handle multiple values for same key well with data dict for [] style,
            # so we format the data tuple-style
            files_list = []
            data_list = []
            data_list.append(('model', 'gpt-transcribe'))
            if stream:
                data_list.append(('stream', 'true'))
            if prompt:
                data_list.append(('prompt', prompt))
            if keywords:
                for kw in keywords:
                    data_list.append(('keywords[]', kw))
            if languages:
                for lang in languages:
                    data_list.append(('languages[]', lang))

            update_task(task_id, phase='sending_to_api')
            response = requests.post(
                url,
                headers={"Authorization": f"Bearer {api_key}"},
                files=[('file', (filename, f, 'application/octet-stream'))],
                data=data_list,
                timeout=(10, 600),
                stream=stream,
            )

        if task_is_cancelled(task_id):
            return
        if response.status_code == 401:
            update_task(task_id, status='error', error='OpenAI APIキーが無効です。設定画面で確認してください。')
            return
        elif response.status_code == 413:
            update_task(task_id, status='error', error='音声ファイルが最大サイズ(25MB)を超えています。')
            return
        elif response.status_code == 429:
            update_task(task_id, status='error', error='OpenAI APIのレート制限に達しました。時間をおいて再度お試しください。')
            return
        elif response.status_code != 200:
            update_task(task_id, status='error', error=f'OpenAI Transcription API Error {response.status_code}')
            return

        update_task(task_id, phase='transcribing')

        if stream:
            full_text = ""
            for line in response.iter_lines():
                if task_is_cancelled(task_id):
                    response.close()
                    return
                if line:
                    decoded = line.decode('utf-8')
                    if decoded.startswith('data: '):
                        payload_str = decoded[6:]
                        if payload_str.strip() == '[DONE]':
                            break
                        try:
                            event = json.loads(payload_str)
                            etype = event.get('type')
                            if etype == 'transcript.text.delta':
                                delta = event.get('delta', '')
                                full_text += delta
                                update_task(task_id, thought='', result=full_text)
                            elif etype == 'transcript.text.done':
                                full_text = event.get('text', full_text)
                                update_task(task_id, thought='', result=full_text)
                        except json.JSONDecodeError:
                            pass
        else:
            result = response.json()
            full_text = result.get('text', '')

        if task_is_cancelled(task_id):
            return

        # Apply word replacements server-side (same as Grok)
        try:
            import re
            with app.app_context():
                active_sets = WordSet.query.filter_by(user_id=user_id, is_active=True).all()
                for s in active_sets:
                    for w in s.words:
                        if w.reading and w.replacement:
                            full_text = re.sub(re.escape(w.reading), w.replacement, full_text, flags=re.IGNORECASE)
        except Exception as e:
            logger.warning(f"Word replacement error: {e}")

        update_task(task_id, status='done', thought='', result=full_text)
        save_history(user_id, action_type, input_summary, '', full_text)

    except requests.exceptions.Timeout:
        update_task(task_id, status='error', error='OpenAI APIへのリクエストがタイムアウトしました。')
    except requests.exceptions.ConnectionError:
        update_task(task_id, status='error', error='OpenAI APIへの接続に失敗しました。')
    except Exception as e:
        logger.error(f"OpenAI Transcription background task {task_id} failed: {e}", exc_info=True)
        update_task(task_id, status='error', error='OpenAI処理中にエラーが発生しました')


def process_openai_gpt_live_transcribe_background(task_id, api_key, audio_filepath, user_id, action_type, input_summary):
    try:
        if task_is_cancelled(task_id):
            return

        import subprocess
        import struct

        # Convert audio to PCM 24kHz 16-bit mono WAV using ffmpeg
        pcm_path = audio_filepath + '.pcm'
        try:
            subprocess.run(
                ['ffmpeg', '-y', '-i', audio_filepath,
                 '-ar', '24000', '-ac', '1', '-f', 's16le', pcm_path],
                capture_output=True, timeout=120, check=True,
            )
        except (subprocess.CalledProcessError, FileNotFoundError):
            update_task(task_id, status='error', error='音声変換に失敗しました。ffmpegが必要です。')
            return

        with open(pcm_path, 'rb') as pcm_file:
            pcm_data = pcm_file.read()
        try:
            os.remove(pcm_path)
        except OSError:
            pass

        if task_is_cancelled(task_id):
            return

        import websocket as ws_client

        full_transcript = ""
        done_event = threading.Event()
        error_message = [None]

        def on_open(ws):
            # Send session.update for transcription session
            session_update = {
                "type": "session.update",
                "session": {
                    "type": "transcription",
                    "audio": {
                        "input": {
                            "format": {"type": "audio/pcm", "rate": 24000},
                            "transcription": {"model": "gpt-live-transcribe"},
                            "turn_detection": None,
                        }
                    }
                }
            }
            ws.send(json.dumps(session_update))
            update_task(task_id, phase='transcribing')
            # Send a small initial chunk to get things started, then stream the rest
            chunk_size = 24000 * 2  # 1 second of PCM16 24kHz
            offset = 0
            while offset < len(pcm_data):
                if task_is_cancelled(None if not globals() else task_id):
                    break
                chunk = pcm_data[offset:offset + chunk_size]
                b64_chunk = base64.b64encode(chunk).decode('utf-8')
                append_event = {
                    "type": "input_audio_buffer.append",
                    "audio": b64_chunk,
                }
                ws.send(json.dumps(append_event))
                offset += chunk_size
                time.sleep(0.05)  # small delay to simulate live streaming
            # Commit the buffer
            commit_event = {"type": "input_audio_buffer.commit"}
            ws.send(json.dumps(commit_event))

        def on_message(ws, message):
            nonlocal full_transcript, error_message, done_event
            try:
                event = json.loads(message)
                etype = event.get('type', '')
                if etype == 'conversation.item.input_audio_transcription.delta':
                    delta = event.get('delta', '')
                    full_transcript += delta
                    update_task(task_id, thought='', result=full_transcript)
                elif etype == 'conversation.item.input_audio_transcription.completed':
                    transcript = event.get('transcript', '')
                    if transcript:
                        full_transcript = transcript
                        update_task(task_id, thought='', result=full_transcript)
                elif etype == 'error':
                    error_message[0] = event.get('error', {}).get('message', 'Unknown WebSocket error')
                    done_event.set()
                elif etype == 'session.updated':
                    pass  # Session ready
                elif etype in ('response.done', 'conversation.item.created'):
                    pass
            except json.JSONDecodeError:
                pass

        def on_error(ws, error):
            error_message[0] = str(error)
            done_event.set()

        def on_close(ws, close_status_code, close_msg):
            done_event.set()

        ws_url = "wss://api.openai.com/v1/realtime?model=gpt-live-transcribe"
        update_task(task_id, phase='sending_to_api')
        ws = ws_client.WebSocketApp(
            ws_url,
            header=["Authorization: Bearer " + api_key],
            on_open=on_open,
            on_message=on_message,
            on_error=on_error,
            on_close=on_close,
        )

        ws_thread = threading.Thread(target=ws.run_forever, kwargs={'sslopt': {"check_hostname": True}})
        ws_thread.daemon = True
        ws_thread.start()

        # Wait for completion with timeout
        done_event.wait(timeout=300)
        ws.close()

        if error_message[0]:
            update_task(task_id, status='error', error=f'OpenAI Realtimeエラー: {error_message[0]}')
            return

        if task_is_cancelled(task_id):
            return

        # Apply word replacements
        text = full_transcript
        try:
            import re
            with app.app_context():
                active_sets = WordSet.query.filter_by(user_id=user_id, is_active=True).all()
                for s in active_sets:
                    for w in s.words:
                        if w.reading and w.replacement:
                            text = re.sub(re.escape(w.reading), w.replacement, text, flags=re.IGNORECASE)
        except Exception as e:
            logger.warning(f"Word replacement error: {e}")

        update_task(task_id, status='done', thought='', result=text)
        save_history(user_id, action_type, input_summary, '', text)

    except Exception as e:
        logger.error(f"OpenAI Live Transcribe background task {task_id} failed: {e}", exc_info=True)
        update_task(task_id, status='error', error='OpenAI Live Transcribe処理中にエラーが発生しました')


# --- Grok STT Background Processor ---
def process_grok_stt_background(task_id, api_key, audio_filepath, user_id, action_type, input_summary):
    try:
        if task_is_cancelled(task_id):
            return
        url = "https://api.x.ai/v1/stt"

        with open(audio_filepath, 'rb') as f:
            content_type_map = {
                '.mp3': 'audio/mpeg',
                '.wav': 'audio/wav',
                '.m4a': 'audio/mp4',
                '.mp4': 'audio/mp4',
                '.webm': 'audio/webm',
                '.ogg': 'audio/ogg',
                '.opus': 'audio/opus',
                '.flac': 'audio/flac',
                '.aac': 'audio/aac',
                '.mkv': 'audio/x-matroska',
            }
            _, ext = os.path.splitext(audio_filepath)
            mime = content_type_map.get(ext.lower(), 'audio/mpeg')
            filename = os.path.basename(audio_filepath)

            # Prepare multipart form data (file must be last)
            files = {'file': (filename, f, mime)}

            update_task(task_id, phase='sending_to_api')
            response = requests.post(
                url,
                headers={"Authorization": f"Bearer {api_key}"},
                files=files,
                timeout=(10, 600)
            )

        if task_is_cancelled(task_id):
            return
        if response.status_code == 401:
            update_task(task_id, status='error', error='xAI APIキーが無効です。設定画面で確認してください。')
            return
        elif response.status_code == 413:
            update_task(task_id, status='error', error='音声ファイルが最大サイズ(500MB)を超えています。')
            return
        elif response.status_code == 429:
            update_task(task_id, status='error', error='xAI APIのレート制限に達しました。時間をおいて再度お試しください。')
            return
        elif response.status_code != 200:
            update_task(task_id, status='error', error=f'xAI STT API Error {response.status_code}')
            return

        update_task(task_id, phase='transcribing')

        result = response.json()
        text = result.get('text', '')

        # Apply word replacements server-side (custom vocabulary)
        try:
            with app.app_context():
                word_list_context = get_word_list_context(user_id)
                if word_list_context:
                    import re
                    active_sets = WordSet.query.filter_by(user_id=user_id, is_active=True).all()
                    for s in active_sets:
                        for w in s.words:
                            if w.reading and w.replacement:
                                text = re.sub(re.escape(w.reading), w.replacement, text, flags=re.IGNORECASE)
        except Exception as e:
            logger.warning(f"Word replacement error: {e}")

        if task_is_cancelled(task_id):
            return
        update_task(task_id, status='done', thought='', result=text)
        save_history(user_id, action_type, input_summary, '', text)

    except requests.exceptions.Timeout:
        update_task(task_id, status='error', error='xAI STT APIへのリクエストがタイムアウトしました。')
    except requests.exceptions.ConnectionError:
        update_task(task_id, status='error', error='xAI STT APIへの接続に失敗しました。')
    except Exception as e:
        logger.error(f"Grok STT background task {task_id} failed: {e}", exc_info=True)
        update_task(task_id, status='error', error='xAI STT処理中にエラーが発生しました')


# --- Task Recovery APIs (Redis-backed) ---
TASK_EXEMPT_ENDPOINTS = {'api.task_stream'}

@app.route('/api/tasks')
@login_required
def list_tasks():
    try:
        task_ids = redis_client.smembers(f"user:{current_user.id}:tasks") or set()
        tasks = []
        for tid in list(task_ids):
            task = get_task(tid)
            if task:
                if task.get('status') == 'running':
                    active_task_id = redis_client.get(f"user:{current_user.id}:active_task")
                    same_server_instance = task.get('server_instance') == str(os.getppid())
                    worker_is_alive = process_is_alive(task.get('worker_pid'))
                    if not same_server_instance or not worker_is_alive or active_task_id != tid:
                        cancel_task(
                            tid,
                            current_user.id,
                            'サービス再起動により処理が中断されました。再度実行してください。',
                        )
                        task = get_task(tid)
                tasks.append({
                    'id': tid,
                    'status': task.get('status'),
                    'action_type': task.get('action_type'),
                    'input_summary': task.get('input_summary'),
                    'thought': task.get('thought', ''),
                    'result': task.get('result', ''),
                    'model': task.get('model'),
                    'created_at': task.get('created_at'),
                })
            else:
                redis_client.srem(f"user:{current_user.id}:tasks", tid)
        # running first, then by recency
        tasks.sort(key=lambda t: (0 if t['status'] == 'running' else 1, -(float(t['created_at']) if t['created_at'] else 0)))
        return jsonify(tasks)
    except Exception as e:
        logger.error(f"Task listing failed for user {current_user.username}: {e}", exc_info=True)
        return jsonify({'error': 'タスク一覧の取得に失敗しました'}), 500

@app.route('/api/tasks/<task_id>/cancel', methods=['POST'])
@login_required
def cancel_task_route(task_id):
    if not cancel_task(task_id, current_user.id):
        return jsonify({'error': 'Task not found'}), 404
    return jsonify({'success': True})

@app.route('/api/task_stream/<task_id>')
@login_required
def task_stream(task_id):
    task = get_task(task_id)
    if not task:
        return jsonify({'error': 'Task not found'}), 404
    if task.get('user_id') != str(current_user.id):
        return jsonify({'error': 'Access denied'}), 403
    return create_stream_response(stream_task_updates(task_id), task_id)

if __name__ == '__main__':
    with app.app_context():
        db.create_all()
    if not os.path.exists(app.config['UPLOAD_FOLDER']):
        os.makedirs(app.config['UPLOAD_FOLDER'])
    app.run(host='127.0.0.1', port=8003)
