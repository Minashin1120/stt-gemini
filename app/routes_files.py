"""ファイル・履歴 API とチャンクアップロード。"""
from datetime import datetime
from datetime import timedelta
from flask import jsonify
from flask import request
from flask import send_from_directory
from flask import session
from flask import url_for
from flask_login import current_user
from flask_login import login_required
from werkzeug.utils import secure_filename
import base64
import fcntl
import json
import os
import secrets
import shutil
import threading
import app as core
from app import is_truthy
from app import GEMINI_STT_MODELS, History, MAX_CHUNKS, MAX_GEMINI_AUDIO_BYTES, MAX_INCOMPLETE_UPLOADS, MAX_OPENAI_AUDIO_BYTES, OPENAI_FILE_STT_MODELS, OPENAI_STT_MODELS, app, check_rate_limit, check_user_model_rate_limit, db, generate_audio_filename, get_active_history_context, get_audio_metadata, get_thinking_level, get_word_list_context, is_plausible_xai_api_key, logger, reject_if_active_task, resolve_user_upload_path, validate_model
from prompts import build_transcription_prompt
from streaming import create_stream_response, process_gemini_background, stream_task_updates
from processors import process_gemini_live_transcribe_background, process_gemini_transcribe_background, process_grok_stt_background
from processors_openai import process_openai_gpt_live_transcribe_background, process_openai_gpt_transcribe_background


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
        if chunk_size <= 0 or chunk_size > core.MAX_CHUNK_BYTES:
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
                if projected_usage > core.MAX_XAI_AUDIO_BYTES:
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
    if model in OPENAI_STT_MODELS:
        max_audio_bytes = MAX_OPENAI_AUDIO_BYTES
    elif model in ('grok-stt', 'grok-live-transcribe'):
        max_audio_bytes = core.MAX_XAI_AUDIO_BYTES
    else:
        max_audio_bytes = MAX_GEMINI_AUDIO_BYTES
    total_size = 0
    for i in range(total_chunks):
        chunk_path = os.path.join(chunks_dir, f'chunk_{i:06d}')
        chunk_size = os.path.getsize(chunk_path)
        if chunk_size <= 0 or chunk_size > core.MAX_CHUNK_BYTES:
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

    if model in GEMINI_STT_MODELS:
        api_key = current_user.get_api_key()
        if not api_key: return jsonify({'error': 'API Key not set'}), 400
        task_id = core.create_task(current_user.id, 'transcribe', 'Audio Input', model)
        target = process_gemini_live_transcribe_background if model.endswith('-live') else process_gemini_transcribe_background
        threading.Thread(target=target, args=(task_id, api_key, filepath, current_user.id, 'transcribe', 'Audio Input'), daemon=True).start()
        return create_stream_response(stream_task_updates(task_id), task_id)

    if model in OPENAI_STT_MODELS:
        api_key = current_user.get_openai_api_key()
        if not api_key:
            return jsonify({'error': 'OpenAI API Key not set. Go to Settings to configure it.'}), 400

        task_id = core.create_task(current_user.id, "transcribe", "Audio Input", model)
        if model in OPENAI_FILE_STT_MODELS:
            target = process_openai_gpt_transcribe_background
            args = (task_id, api_key, filepath, current_user.id, "transcribe", "Audio Input")
            kwargs = {'model': model, 'stream': model != 'whisper-1', 'diarize': model == 'gpt-4o-transcribe-diarize'}
        else:
            target = process_openai_gpt_live_transcribe_background
            args = (task_id, api_key, filepath, current_user.id, "transcribe", "Audio Input")
            kwargs = {'model': model}
        thread = threading.Thread(target=target, args=args, kwargs=kwargs)
        thread.daemon = True
        thread.start()
        return create_stream_response(stream_task_updates(task_id), task_id)

    if model in ('grok-stt', 'grok-live-transcribe'):
        api_key = current_user.get_xai_api_key()
        if not is_plausible_xai_api_key(api_key):
            return jsonify({'error': 'xAI APIキーが未設定か、形式が正しくありません。設定画面で確認してください。'}), 400

        task_id = core.create_task(current_user.id, "transcribe", "Audio Input", model)
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

    task_id = core.create_task(current_user.id, "transcribe", "Audio Input", model)
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
