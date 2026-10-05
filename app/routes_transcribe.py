"""/transcribe・/reanalyze・/improve など文字起こし系ルート。"""
from flask import jsonify
from flask import request
from flask import session
from flask_login import current_user
from flask_login import login_required
import base64
import os
import threading
import app as core
from app import is_truthy
from app import GEMINI_STT_MODELS, History, MAX_INSTRUCTION_LENGTH, MAX_TEXT_LENGTH, OPENAI_FILE_STT_MODELS, OPENAI_STT_MODELS, app, apply_word_replacements, check_user_model_rate_limit, db, get_active_history_context, get_thinking_level, get_word_list_context, is_plausible_xai_api_key, reject_if_active_task, resolve_user_upload_path, save_uploaded_audio_file, validate_model
from prompts import TEXT_REPHRASE_CORRECTION_PROMPT, build_improve_prompt, build_reanalyze_prompt, build_transcription_prompt
from streaming import create_stream_response, process_gemini_background, stream_task_updates
from processors import process_gemini_live_transcribe_background, process_gemini_transcribe_background, process_grok_stt_background
from processors_openai import process_openai_gpt_live_transcribe_background, process_openai_gpt_transcribe_background



@app.route('/transcribe', methods=['POST'])
@login_required
def transcribe():
    reject_if_active_task()
    if not check_user_model_rate_limit():
        return jsonify({'error': '処理回数が上限を超えました。時間をおいて再度お試しください。'}), 429
    model = validate_model(request.form.get('model', 'gemini-3.5-flash'))
    
    file = request.files.get('audio_file')
    if not file: return jsonify({'error': 'No file'}), 400

    filename, filepath, mime_type = save_uploaded_audio_file(file, request.form.get('is_append'))
    if not filename:
        return jsonify({'error': '対応していない音声形式です'}), 400

    if model in GEMINI_STT_MODELS:
        api_key = current_user.get_api_key()
        if not api_key: return jsonify({'error': 'API Key not set'}), 400
        task_id = core.create_task(current_user.id, 'transcribe', 'Audio Input', model)
        target = process_gemini_live_transcribe_background if model.endswith('-live') else process_gemini_transcribe_background
        threading.Thread(target=target, args=(task_id, api_key, filepath, current_user.id, 'transcribe', 'Audio Input'), daemon=True).start()
        return create_stream_response(stream_task_updates(task_id), task_id)

    if model in OPENAI_STT_MODELS:
        api_key = current_user.get_openai_api_key()
        if not api_key: return jsonify({'error': 'OpenAI API Key not set. Go to Settings to configure it.'}), 400
        
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
        # grok-live-transcribeは通常WebSocket(/ws/grok_live)経由だが、ファイルアップロード等で
        # ここに来た場合は静的ファイルなのでバッチのGrok STTとして扱う。
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
    
    task_id = core.create_task(current_user.id, "transcribe", "Audio Input", model)
    thread = threading.Thread(
        target=process_gemini_background,
        args=(task_id, api_key, payload, current_user.id, "transcribe", "Audio Input", model)
    )
    thread.daemon = True
    thread.start()
    return create_stream_response(stream_task_updates(task_id), task_id)



@app.route('/transcribe_live_finalize', methods=['POST'])
@login_required
def transcribe_live_finalize():
    # このエンドポイント自体はcreate_task()を呼ばない(バックグラウンド処理を伴わない同期処理のため)。
    # /ws/grok_liveのセッション終了直後に呼ばれるが、そのfinallyブロックが
    # アクティブタスクロックを解放し終わる前にここへ到達することがあるため、
    # reject_if_active_task()は呼ばない(呼ぶと直前の正常終了なのに409になり得る)。
    file = request.files.get('audio_file')
    if not file: return jsonify({'error': 'No file'}), 400

    filename, filepath, mime_type = save_uploaded_audio_file(file, request.form.get('is_append'))
    if not filename:
        return jsonify({'error': '対応していない音声形式です'}), 400

    text = (request.form.get('text') or '').strip()
    text = apply_word_replacements(current_user.id, text)
    core.save_history(current_user.id, "transcribe", "Live Audio (Grok)", '', text)
    return jsonify({'text': text})

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

    if model in GEMINI_STT_MODELS:
        api_key = current_user.get_api_key()
        if not api_key: return jsonify({'error': 'API Key not set'}), 400
        task_id = core.create_task(current_user.id, 'reanalyze', 'Re-analysis Request', model)
        target = process_gemini_live_transcribe_background if model.endswith('-live') else process_gemini_transcribe_background
        threading.Thread(target=target, args=(task_id, api_key, filepath, current_user.id, 'reanalyze', 'Re-analysis Request'), daemon=True).start()
        return create_stream_response(stream_task_updates(task_id), task_id)

    if model in OPENAI_STT_MODELS:
        api_key = current_user.get_openai_api_key()
        if not api_key: return jsonify({'error': 'OpenAI API Key not set'}), 400
        
        task_id = core.create_task(current_user.id, "reanalyze", "Re-analysis Request", model)
        if model in OPENAI_FILE_STT_MODELS:
            target = process_openai_gpt_transcribe_background
            args = (task_id, api_key, filepath, current_user.id, "reanalyze", "Re-analysis Request")
            kwargs = {'model': model, 'stream': model != 'whisper-1', 'diarize': model == 'gpt-4o-transcribe-diarize'}
        else:
            target = process_openai_gpt_live_transcribe_background
            args = (task_id, api_key, filepath, current_user.id, "reanalyze", "Re-analysis Request")
            kwargs = {'model': model}
        thread = threading.Thread(target=target, args=args, kwargs=kwargs)
        thread.daemon = True
        thread.start()
        return create_stream_response(stream_task_updates(task_id), task_id)
    
    if model in ('grok-stt', 'grok-live-transcribe'):
        api_key = current_user.get_xai_api_key()
        if not is_plausible_xai_api_key(api_key):
            return jsonify({'error': 'xAI APIキーが未設定か、形式が正しくありません。'}), 400

        task_id = core.create_task(current_user.id, "reanalyze", "Re-analysis Request", model)
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
    prompt = build_reanalyze_prompt(history_context, word_list_context, allow_rephrase_correction, allow_filler_removal, is_lite)
    
    payload = {
        "contents": [{"parts": [
            {"text": prompt},
            {"inline_data": {"mime_type": session.get('last_audio_mime') or 'audio/mpeg', "data": audio_b64}}
        ]}],
        "generationConfig": {"thinkingConfig": {"includeThoughts": True, "thinkingLevel": get_thinking_level(data.get('thinking_level'))}}
    }
    task_id = core.create_task(current_user.id, "reanalyze", "Re-analysis Request", model)
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
    prompt = build_improve_prompt(history_context, word_list_context, text, instruction)
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
    if model in {'grok-stt', 'grok-live-transcribe'} | OPENAI_STT_MODELS | GEMINI_STT_MODELS:
        model = 'gemini-3.5-flash'  # Grok/OpenAI STT cannot do text improvement
    task_id = core.create_task(current_user.id, "improve", instruction, model)
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
    if model in {'grok-stt', 'grok-live-transcribe'} | OPENAI_STT_MODELS | GEMINI_STT_MODELS:
        model = 'gemini-3.5-flash'  # Grok/OpenAI STT cannot do text correction
    summary = "Rephrase correction (text only)"
    task_id = core.create_task(current_user.id, "correct_rephrase", summary, model)
    thread = threading.Thread(
        target=process_gemini_background,
        args=(task_id, api_key, payload, current_user.id, "correct_rephrase", summary, model)
    )
    thread.daemon = True
    thread.start()
    return create_stream_response(stream_task_updates(task_id), task_id)
