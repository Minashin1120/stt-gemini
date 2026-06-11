import os
import json
import requests
import base64
import time
import threading
import secrets
import logging
from datetime import datetime, timedelta
from flask import Flask, render_template, request, redirect, url_for, flash, jsonify, Response, stream_with_context, session, send_from_directory
from flask_login import LoginManager, UserMixin, login_user, login_required, logout_user, current_user
from flask_sqlalchemy import SQLAlchemy
from cryptography.fernet import Fernet
from werkzeug.security import generate_password_hash, check_password_hash
from werkzeug.utils import secure_filename
from dotenv import load_dotenv

load_dotenv()

# Setup logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s [%(levelname)s] %(message)s')
logger = logging.getLogger(__name__)

app = Flask(__name__)
app.config['SECRET_KEY'] = os.getenv('SECRET_KEY')
app.config['SQLALCHEMY_DATABASE_URI'] = os.getenv('SQLALCHEMY_DATABASE_URI')
app.config['UPLOAD_FOLDER'] = os.path.join(app.root_path, 'uploads')
app.config['MAX_CONTENT_LENGTH'] = 32 * 1024 * 1024
app.config['PERMANENT_SESSION_LIFETIME'] = timedelta(days=31)

db = SQLAlchemy(app)
login_manager = LoginManager()
login_manager.init_app(app)
login_manager.login_view = 'welcome'

fernet = Fernet(os.getenv('ENCRYPTION_KEY').encode())

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
        if not session_token or not request_token or request_token != session_token:
            return jsonify({'error': 'CSRF validation failed'}), 400

# --- Models ---
class User(UserMixin, db.Model):
    id = db.Column(db.Integer, primary_key=True)
    username = db.Column(db.String(150), unique=True, nullable=False)
    password = db.Column(db.String(255), nullable=False)
    encrypted_api_key = db.Column(db.Text, nullable=True)
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
    msg['Subject'] = f"[stt-gemini] ロック解除申請: {username}"
    msg['From'] = os.getenv('MAIL_DEFAULT_SENDER', 'noreply@stt-gemini.minashin1120.com')
    msg['To'] = admin_email

    try:
        # Exim4 (sendmail互換コマンド) を使用
        process = subprocess.Popen(['/usr/sbin/sendmail', '-t', '-oi'], stdin=subprocess.PIPE)
        process.communicate(msg.as_bytes())
    except Exception as e:
        print(f"Exim4 email notification failed: {e}")

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

    ext_to_mime = {
        '.mp3': 'audio/mpeg',
        '.wav': 'audio/wav',
        '.m4a': 'audio/mp4',
        '.mp4': 'audio/mp4',
        '.webm': 'audio/webm',
        '.ogg': 'audio/ogg',
    }

    if ext not in ext_to_mime:
        ext = '.mp3'

    return ext, ext_to_mime[ext]

def resolve_user_upload_path(filename, user_id):
    if not filename or not filename.startswith(f"user_{user_id}_"):
        return None

    upload_dir = os.path.abspath(app.config['UPLOAD_FOLDER'])
    full_path = os.path.abspath(os.path.join(upload_dir, filename))
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
                                    print(f"File removal error: {file_err}")
                
                db.session.commit()
        except Exception as e:
            print(f"Cleanup error: {e}")
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
        print(f"History save error: {e}")

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
        # Turnstile Check
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
        
        username = request.form.get('username')
        password = request.form.get('password')
        if User.query.filter_by(username=username).first():
            flash('ユーザー名が重複しています。')
            return redirect(url_for('register'))
        new_user = User(username=username, password=generate_password_hash(password))
        db.session.add(new_user); db.session.commit()
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
            js_challenge = req.get_json(silent=True).get('_js_challenge')
            
        csrf_token = session.get('csrf_token')
        # Expecting 'valid_<csrf_token>' set by client-side JS
        if not js_challenge or js_challenge != f"valid_{csrf_token}":
            logger.warning(f"Atypical client: JS Challenge failed or missing. Expected 'valid_{csrf_token}', got '{js_challenge}'")
            return True

        # Honey-pot field check (Highly sensitive)
        if req.form.get('_honey_field'):
            logger.warning(f"Atypical client: Honey-pot field filled: {req.form.get('_honey_field')}")
            return True

    return False

@app.route('/login', methods=['GET', 'POST'])
def login():
    if request.method == 'POST':
        # Turnstile Check
        turnstile_token = request.form.get('cf-turnstile-response')
        if not verify_turnstile(turnstile_token):
            flash('ロボットではないことを証明してください。')
            return redirect(url_for('login'))

        # フォーム表示からの経過時間をチェック
        load_time = session.pop('_form_load_time', 0)
        user = User.query.filter_by(username=request.form.get('username')).first()
        
        if time.time() - load_time < 0.5:
            return "Access Denied: Unnatural submission speed.", 403

        username = request.form.get('username')
        password = request.form.get('password')
        
        # 不審なクライアントチェック (ここでは拒否するが永続ロックはしない)
        if is_atypical_client(request):
            flash('不審なクライアントからのアクセスが検知されました。ブラウザの設定を確認してください。')
            return redirect(url_for('login'))

        if user:
            if user.is_locked:
                flash('アカウントがロックされています。解除が必要な場合は下記から申請してください。')
                return redirect(url_for('login'))
            if check_password_hash(user.password, password):
                login_user(user, remember=True)
                return redirect(url_for('index'))
        flash('ログイン失敗。')
    
    # フォーム表示時刻を記録
    session['_form_load_time'] = time.time()
    return render_template('login.html')

@app.route('/request_unlock', methods=['GET', 'POST'])
def request_unlock():
    if request.method == 'POST':
        # Turnstile Check
        turnstile_token = request.form.get('cf-turnstile-response')
        if not verify_turnstile(turnstile_token):
            flash('ロボットではないことを証明してください。')
            return redirect(url_for('request_unlock'))

        if is_atypical_client(request):
            flash('不審な操作が検知されました。ブラウザの設定や拡張機能を確認してください。')
            return redirect(url_for('request_unlock'))

        username = request.form.get('username')
        user = User.query.filter_by(username=username).first()
        if user:
            if not user.is_locked:
                flash('このアカウントはロックされていません。')
                return redirect(url_for('login'))
            
            if user.unlock_requested:
                flash('解除申請は既に送信済みです。管理者の対応をお待ちください。')
                return redirect(url_for('login'))
            
            user.unlock_requested = True
            db.session.commit()
            notify_admin_unlock(username)
            flash('解除申請を送信しました。管理者の対応をお待ちください。')
            return redirect(url_for('login'))
        
        flash('ユーザーが見つかりません。')
        return redirect(url_for('request_unlock'))

    return render_template('request_unlock.html')

@app.route('/logout')
def logout():
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
        retention_minutes = request.form.get('retention_minutes')
        
        if api_key:
            current_user.set_api_key(api_key)
            flash('APIキーを保存しました。')
        
        if retention_minutes is not None:
            try:
                retention_value = int(retention_minutes)
                if retention_value < 1 or retention_value > 1440:
                    flash('保存期間は1〜1440分の範囲で指定してください。')
                else:
                    current_user.retention_minutes = retention_value
                    db.session.commit()
                    flash('保存期間の設定を更新しました。')
            except ValueError:
                flash('保存期間には数値を入力してください。')
        
        db.session.commit()
    api_key_value = current_user.get_api_key() or ''
    return render_template('settings.html', has_key=current_user.encrypted_api_key is not None, api_key=api_key_value)

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
                    try: os.remove(os.path.join(app.config['UPLOAD_FOLDER'], f))
                    except: pass
        
        # 2. データベースレコードの削除 (History, WordSet, User)
        History.query.filter_by(user_id=user_id).delete()
        WordSet.query.filter_by(user_id=user_id).delete()
        user = db.session.get(User, user_id)
        db.session.delete(user)
        db.session.commit()
        
        logout_user()
        session.clear()
        return jsonify({'success': True})
    except Exception as e:
        db.session.rollback()
        return jsonify({'error': str(e)}), 500

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

@app.route('/api/words/delete/<int:word_id>', methods=['POST'])
@login_required
def delete_word(word_id):
    w = Word.query.join(WordSet).filter(Word.id == word_id, WordSet.user_id == current_user.id).first()
    if w:
        db.session.delete(w)
        db.session.commit()
        return jsonify({'success': True})
    return jsonify({'error': 'Not found'}), 404

# --- Streaming Generator with History Saving ---
def stream_gemini_and_save(api_key, payload, user_id, action_type, input_summary, model="gemini-3-flash-preview"):
    # gemini-3.1-flash-lite は LOW/MEDIUM で thought を返さないため、
    # ユーザーが期待する thought 表示を得られるようレベルを自動調整する
    if model == "gemini-3.1-flash-lite":
        tc = payload.get("generationConfig", {}).get("thinkingConfig", {})
        level = tc.get("thinkingLevel", "").upper() if tc else ""
        if level in ("LOW", "MEDIUM"):
            payload.setdefault("generationConfig", {}).setdefault("thinkingConfig", {})["thinkingLevel"] = "HIGH"
    
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse&key={api_key}"
    
    full_thought = ""
    full_text = ""
    had_error = False
    
    max_retries = 3
    retry_delay = 2
    
    for attempt in range(max_retries + 1):
        try:
            response = requests.post(url, headers={'Content-Type': 'application/json'}, json=payload, stream=True, timeout=(10, None))
            
            if response.status_code == 429 and attempt < max_retries:
                yield f"data: {json.dumps({'type': 'info', 'content': f'API制限中({retry_delay}秒後に再試行...)'})}\n\n"
                time.sleep(retry_delay)
                retry_delay *= 2
                continue
            
            if response.status_code != 200:
                had_error = True
                if response.status_code == 429:
                    yield f"data: {json.dumps({'type': 'error', 'content': 'API Error 429: リクエスト制限に達しました。時間をおいて再度お試しください。'})}\n\n"
                else:
                    yield f"data: {json.dumps({'type': 'error', 'content': f'API Error {response.status_code}'})}\n\n"
                return

            for line in response.iter_lines():
                if line:
                    decoded = line.decode('utf-8')
                    if decoded.startswith('data: '):
                        try:
                            data = json.loads(decoded[6:])
                            parts = data.get('candidates', [{}])[0].get('content', {}).get('parts', [])
                            for p in parts:
                                if p.get('thought'): # Gemini 3.0 thought field
                                    content = p.get('text', '')
                                    if not content:
                                        continue
                                    full_thought += content
                                    yield f"data: {json.dumps({'type': 'thought', 'content': content})}\n\n"
                                elif 'text' in p:
                                    content = p['text']
                                    full_text += content
                                    yield f"data: {json.dumps({'type': 'text', 'content': content})}\n\n"
                        except: pass
            break
        except Exception as e:
            had_error = True
            yield f"data: {json.dumps({'type': 'error', 'content': str(e)})}\n\n"
            return
    
    if had_error:
        return

    # 完了後、履歴に保存 (別スレッドで実行してレスポンスをブロックしない手もあるが、今回はここで)
    # コンテキスト内での実行が必要
    save_history(user_id, action_type, input_summary, full_thought, full_text)
    
    yield f"data: {json.dumps({'type': 'done'})}\n\n"

def create_stream_response(generator):
    response = Response(stream_with_context(generator), mimetype='text/event-stream')
    response.headers['Cache-Control'] = 'no-cache'
    response.headers['X-Accel-Buffering'] = 'no'
    return response

# プロンプト定数
VERBATIM_INSTRUCTION = """
STRICT INSTRUCTION:
1. Transcribe the audio exactly as spoken (Verbatim).
2. Do NOT use your internal knowledge to correct facts, dates, or years.
3. Output ONLY the text. No preamble.
4. Do NOT insert line breaks in the middle of a sentence, even if there is a pause in the speech. Only use line breaks at the end of a complete sentence or when the speaker/topic changes significantly.
"""

@app.route('/transcribe', methods=['POST'])
@login_required
def transcribe():
    api_key = current_user.get_api_key()
    if not api_key: return jsonify({'error': 'API Key not set'}), 400
    
    file = request.files.get('audio_file')
    if not file: return jsonify({'error': 'No file'}), 400

    # 受信ファイル名は必ず安全化する。拡張子は許可済みのものだけ使う。
    ext, mime_type = get_audio_metadata(file.filename, file.mimetype)
    
    filename = f"user_{current_user.id}_{int(time.time())}{ext}"
    if not os.path.exists(app.config['UPLOAD_FOLDER']):
        os.makedirs(app.config['UPLOAD_FOLDER'])
        
    filepath = os.path.join(app.config['UPLOAD_FOLDER'], filename)
    file.save(filepath)
    session['last_audio_file'] = filename
    session['last_audio_mime'] = mime_type
    
    with open(filepath, "rb") as f:
        audio_b64 = base64.b64encode(f.read()).decode('utf-8')
    
    # 履歴コンテキスト取得
    history_context = get_active_history_context(current_user.id)
    word_list_context = get_word_list_context(current_user.id)
    
    full_prompt = f"{history_context}\n{word_list_context}\nTASK: {VERBATIM_INSTRUCTION}"
    
    payload = {
        "contents": [{"parts": [
            {"text": full_prompt},
            {"inline_data": {"mime_type": mime_type, "data": audio_b64}}
        ]}],
        "generationConfig": {"thinkingConfig": {"includeThoughts": True, "thinkingLevel": request.form.get('thinking_level', 'LOW').upper()}}
    }
    
    model = request.form.get('model', 'gemini-3-flash-preview')
    
    return create_stream_response(stream_gemini_and_save(api_key, payload, current_user.id, "transcribe", "Audio Input", model=model))

@app.route('/reanalyze', methods=['POST'])
@login_required
def reanalyze():
    api_key, filename = current_user.get_api_key(), session.get('last_audio_file')
    if not api_key or not filename: return jsonify({'error': 'ファイルなし'}), 400
    filepath = os.path.join(app.config['UPLOAD_FOLDER'], filename)
    if not os.path.exists(filepath): return jsonify({'error': '期限切れ'}), 400
    data = request.get_json(silent=True) or {}
    
    with open(filepath, "rb") as f:
        audio_b64 = base64.b64encode(f.read()).decode('utf-8')
    
    history_context = get_active_history_context(current_user.id)
    word_list_context = get_word_list_context(current_user.id)
    prompt = f"""
    {history_context}
    {word_list_context}
    
    CONTEXT: The user pressed 'Re-analyze'.
    TASK: Listen again carefully and transcribe exactly.
    Do NOT insert line breaks in the middle of a sentence, even if there is a pause in the speech.
    """
    
    payload = {
        "contents": [{"parts": [
            {"text": prompt},
            {"inline_data": {"mime_type": session.get('last_audio_mime') or 'audio/mpeg', "data": audio_b64}}
        ]}],
        "generationConfig": {"thinkingConfig": {"includeThoughts": True, "thinkingLevel": data.get('thinking_level', 'LOW').upper()}}
    }
    model = data.get('model', 'gemini-3-flash-preview')
    return create_stream_response(stream_gemini_and_save(api_key, payload, current_user.id, "reanalyze", "Re-analysis Request", model=model))

@app.route('/improve', methods=['POST'])
@login_required
def improve():
    api_key, data = current_user.get_api_key(), request.get_json(silent=True) or {}
    text = data.get('text')
    instruction = data.get('instruction')
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
            filepath = os.path.join(app.config['UPLOAD_FOLDER'], filename)
            if os.path.exists(filepath):
                with open(filepath, "rb") as f:
                    audio_b64 = base64.b64encode(f.read()).decode('utf-8')
                parts.append({"text": "Reference Audio:"})
                parts.append({"inline_data": {"mime_type": session.get('last_audio_mime', 'audio/mp3'), "data": audio_b64}})

    payload = {
        "contents": [{"parts": parts}],
        "generationConfig": {"thinkingConfig": {"includeThoughts": True, "thinkingLevel": data.get('thinking_level', 'LOW').upper()}}
    }
    model = data.get('model', 'gemini-3-flash-preview')
    return create_stream_response(stream_gemini_and_save(api_key, payload, current_user.id, "improve", instruction, model=model))

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
                print(f"delete_audio error: {e}")
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
                        files.append({'filename': f, 'display_name': dt.strftime('%Y/%m/%d %H:%M:%S'), 'url': url_for('uploaded_file', filename=f)})
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
            print(f"delete_specific_file error: {e}")
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
        return jsonify({'error': str(e)}), 500

@app.route('/api/clear_history', methods=['POST'])
@login_required
def clear_history():
    try:
        History.query.filter_by(user_id=current_user.id).delete()
        db.session.commit()
        return jsonify({'success': True})
    except Exception as e:
        db.session.rollback()
        return jsonify({'error': str(e)}), 500

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
                        print(f"clear_all file removal error: {e}")
                        continue
        
        # 3. セッション変数をクリア
        session.pop('last_audio_file', None)
        session.pop('last_audio_mime', None)
        
        return jsonify({'success': True})
    except Exception as e:
        db.session.rollback()
        return jsonify({'error': str(e)}), 500

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

if __name__ == '__main__':
    with app.app_context():
        db.create_all()
    if not os.path.exists(app.config['UPLOAD_FOLDER']):
        os.makedirs(app.config['UPLOAD_FOLDER'])
    app.run(host='0.0.0.0', port=8003)
