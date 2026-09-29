"""認証・設定・APIキー・アカウント削除のルート。"""
from flask import flash
from flask import jsonify
from flask import redirect
from flask import render_template
from flask import request
from flask import session
from flask import url_for
from flask_login import current_user
from flask_login import login_required
from flask_login import login_user
from flask_login import logout_user
from sqlalchemy.exc import IntegrityError
from werkzeug.security import check_password_hash
from werkzeug.security import generate_password_hash
import os
import secrets
import shutil
import threading
import time
import app as core
from app import is_atypical_client
from app import History, MAX_API_KEY_LENGTH, OPENAI_STT_MODELS, User, WordSet, app, check_rate_limit, db, delete_task, is_plausible_xai_api_key, logger, notify_admin_unlock
from routes_files import get_user_chunks_root


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
        if not core.verify_turnstile(turnstile_token):
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
        session.permanent = True
        return redirect(url_for('settings'))
    
    # フォーム表示時刻を記録
    session['_form_load_time'] = time.time()
    return render_template('register.html')


@app.route('/login', methods=['GET', 'POST'])
def login():
    if request.method == 'POST':
        if not check_rate_limit('login', 5, 60):
            flash('試行回数が多すぎます。しばらく経ってから再度お試しください。')
            return redirect(url_for('login'))

        turnstile_token = request.form.get('cf-turnstile-response')

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

        # Turnstile検証(ネットワークI/O)とパスワード検証(scrypt計算)を並行実行し、
        # ログイン成功〜リダイレクトまでの応答時間を短縮する。
        # どちらも認証結果に必須のため、両方の完了を待ってから判定する。
        turnstile_result = {}
        def _verify_turnstile_async():
            turnstile_result['ok'] = core.verify_turnstile(turnstile_token)
        turnstile_thread = threading.Thread(target=_verify_turnstile_async, daemon=True)
        turnstile_thread.start()

        user = User.query.filter_by(username=username).first()

        # 定数時間比較: ユーザーが存在しない場合もダミーハッシュで比較し、タイミング差をなくす
        if user:
            password_valid = check_password_hash(user.password, password)
        else:
            # 存在しないユーザーの場合もダミーハッシュで比較 (時間差による列挙防止)
            dummy_hash = generate_password_hash('dummy_value_for_timing')
            check_password_hash(dummy_hash, password)
            password_valid = False

        turnstile_thread.join()
        turnstile_ok = turnstile_result.get('ok', False)

        if not turnstile_ok:
            flash('ロボットではないことを証明してください。')
            return redirect(url_for('login'))

        # 元の実装と同様に、まずパスワード検証の成否を優先する
        # (ロック状態は正しいパスワードを知るユーザーにのみ開示する)
        if not password_valid:
            flash('ログイン失敗。')
            # フォーム表示時刻を再記録して再表示（失敗後も連続送信が弾かれないようにする）
            session['_form_load_time'] = time.time()
            return render_template('login.html')

        if user.is_locked:
            flash('アカウントがロックされています。解除が必要な場合は下記から申請してください。')
            return redirect(url_for('login'))

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
        if not core.verify_turnstile(turnstile_token):
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
        xai_api_key = (request.form.get('xai_api_key') or '').strip()
        openai_api_key = request.form.get('openai_api_key')
        retention_minutes = request.form.get('retention_minutes')
        
        if api_key and len(api_key) <= MAX_API_KEY_LENGTH:
            current_user.set_api_key(api_key)
            flash('Gemini APIキーを保存しました。')
        elif api_key:
            flash('Gemini APIキーが長すぎます。')
        
        if xai_api_key and not is_plausible_xai_api_key(xai_api_key):
            flash('xAI APIキーの形式が正しくありません（xai- で始まるキーを入力してください）。')
        elif xai_api_key and len(xai_api_key) <= MAX_API_KEY_LENGTH:
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
        has_xai_key=is_plausible_xai_api_key(current_user.get_xai_api_key()),
        has_openai_key=current_user.encrypted_openai_api_key is not None,
    )

@app.route('/api/check_api_keys', methods=['GET'])
@login_required
def check_api_keys():
    return jsonify({
        'has_gemini_key': current_user.encrypted_api_key is not None,
        'has_xai_key': is_plausible_xai_api_key(current_user.get_xai_api_key()),
        'has_openai_key': current_user.encrypted_openai_api_key is not None,
    })

@app.route('/api/check_api_key', methods=['POST'])
@login_required
def check_api_key():
    data = request.get_json(silent=True) or {}
    model = data.get('model', '')
    if model in OPENAI_STT_MODELS:
        has_key = current_user.encrypted_openai_api_key is not None
    elif model in ('grok-stt', 'grok-live-transcribe'):
        has_key = is_plausible_xai_api_key(current_user.get_xai_api_key())
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
        if not is_plausible_xai_api_key(api_key):
            return jsonify({'error': 'xAI APIキーの形式が正しくありません（xai- で始まるキーを入力してください）'}), 400
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

        for task_id in core.redis_client.smembers(f"user:{user_id}:tasks") or set():
            delete_task(task_id)
        core.redis_client.delete(f"user:{user_id}:tasks", f"user:{user_id}:active_task")
        
        session.clear()
        logout_user()
        return jsonify({'success': True})
    except Exception as e:
        db.session.rollback()
        logger.error(f"Account deletion failed for user {current_user.username}: {e}", exc_info=True)
        return jsonify({'error': 'アカウント削除に失敗しました'}), 500
