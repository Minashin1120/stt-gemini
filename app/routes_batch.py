"""/api/batches : Gemini Batch API ジョブの投入・一覧・取り込み・取消・削除、/batch ページ。"""
from flask import jsonify, render_template, request, session
from flask_login import current_user, login_required
import os
import app as core
import batch
from app import History, MAX_GEMINI_AUDIO_BYTES, MAX_INSTRUCTION_LENGTH, MAX_TEXT_LENGTH, app, check_user_model_rate_limit, db, get_active_history_context, get_audio_metadata, get_thinking_level, get_word_list_context, is_truthy, resolve_user_upload_path, validate_model
from prompts import build_improve_prompt, build_reanalyze_prompt, build_transcription_prompt

# 一覧に出す件数の上限（新しい順）
LIST_LIMIT = 100


def _audio_part(api_key, data, mime, name):
    f = batch.wait_file_active(api_key, batch.upload_file(api_key, data, mime, name))
    return {'file_data': {'file_uri': f['uri'], 'mime_type': mime}}


def _read_session_audio():
    filename = session.get('last_audio_file')
    if not filename:
        return None, None, 'ファイルなし'
    path = resolve_user_upload_path(filename, current_user.id)
    if not path or not os.path.exists(path):
        return None, None, '期限切れ'
    return open(path, 'rb').read(), session.get('last_audio_mime') or 'audio/mpeg', None


@app.route('/batch')
@login_required
def batch_page():
    return render_template('batch.html')


@app.route('/api/batches', methods=['GET'])
@login_required
def list_batches():
    """一覧。進行中のジョブだけ provider に問い合わせて最新化する。"""
    jobs = batch.BatchJob.query.filter_by(user_id=current_user.id).order_by(batch.BatchJob.created_at.desc()).limit(LIST_LIMIT).all()
    api_key = current_user.get_api_key()
    if api_key and request.args.get('refresh', '1') != '0':
        for job in jobs:
            batch.refresh_job(job, api_key)
    return jsonify([j.to_dict() for j in jobs])


@app.route('/api/batches', methods=['POST'])
@login_required
def submit_batch():
    if not check_user_model_rate_limit():
        return jsonify({'error': '処理回数が上限を超えました。時間をおいて再度お試しください。'}), 429
    model = validate_model(request.form.get('model', 'gemini-3.5-flash'))
    if model not in batch.BATCH_MODELS:
        return jsonify({'error': 'このモデルはBatch処理に対応していません（Gemini 通常モデルのみ対応）'}), 400
    api_key = current_user.get_api_key()
    if not api_key:
        return jsonify({'error': 'API Key not set'}), 400
    running = batch.BatchJob.query.filter_by(user_id=current_user.id, status='running').count()
    if running >= batch.MAX_RUNNING_JOBS:
        return jsonify({'error': f'進行中のBatchが上限({batch.MAX_RUNNING_JOBS}件)に達しています'}), 429

    action = request.form.get('action', 'transcribe')
    thinking = get_thinking_level(request.form.get('thinking_level'))
    history_context = get_active_history_context(current_user.id)
    word_list_context = get_word_list_context(current_user.id)
    is_lite = model in ('gemini-3.5-flash-lite', 'gemini-3.1-flash-lite')
    rephrase = is_truthy(request.form.get('allow_rephrase_correction'))
    filler = is_truthy(request.form.get('allow_filler_removal'))

    try:
        if action == 'transcribe':
            file = request.files.get('audio_file')
            if not file:
                return jsonify({'error': 'No file'}), 400
            ext, mime = get_audio_metadata(file.filename, file.mimetype)
            if not ext:
                return jsonify({'error': '対応していない音声形式です'}), 400
            data = file.read()
            if len(data) > MAX_GEMINI_AUDIO_BYTES:
                return jsonify({'error': '音声ファイルが上限サイズを超えています'}), 413
            prompt = build_transcription_prompt(
                history_context, word_list_context,
                "The user enabled rephrase correction mode for this transcription.",
                allow_rephrase_correction=rephrase, allow_filler_removal=filler, is_lite_model=is_lite)
            parts = [{'text': prompt}, _audio_part(api_key, data, mime, file.filename or 'audio')]
            summary = 'Audio Input (Batch)'
        elif action == 'reanalyze':
            data, mime, err = _read_session_audio()
            if err:
                return jsonify({'error': err}), 400
            prompt = build_reanalyze_prompt(history_context, word_list_context, rephrase, filler, is_lite)
            parts = [{'text': prompt}, _audio_part(api_key, data, mime, 'reanalyze')]
            summary = 'Re-analysis Request (Batch)'
        elif action == 'improve':
            text = request.form.get('text') or ''
            instruction = request.form.get('instruction') or ''
            if not text or not instruction:
                return jsonify({'error': 'テキストと指示を入力してください'}), 400
            if len(text) > MAX_TEXT_LENGTH or len(instruction) > MAX_INSTRUCTION_LENGTH:
                return jsonify({'error': '入力が長すぎます'}), 413
            # 通常の /improve と同様、手動修正を最新の履歴に反映してから文脈を作る
            last_h = History.query.filter_by(user_id=current_user.id).order_by(History.timestamp.desc()).first()
            if last_h:
                last_h.result_text = text
                db.session.commit()
                history_context = get_active_history_context(current_user.id)
            parts = [{'text': build_improve_prompt(history_context, word_list_context, text, instruction)}]
            if is_truthy(request.form.get('use_audio')):
                data, mime, err = _read_session_audio()
                if not err:
                    parts.append({'text': 'Reference Audio:'})
                    parts.append(_audio_part(api_key, data, mime, 'reference'))
            summary = instruction
        else:
            return jsonify({'error': 'Invalid action'}), 400

        body = {'contents': [{'parts': parts}],
                'generation_config': {'thinking_config': {'include_thoughts': True, 'thinking_level': thinking}}}
        name = batch.create_batch(api_key, model, body, f'voxcribe-{current_user.id}-{action}')
    except Exception as e:
        core.logger.error(f'batch submit failed: {e}', exc_info=True)
        return jsonify({'error': f'Batchの投入に失敗しました: {e}'}), 502

    job = batch.BatchJob(user_id=current_user.id, provider_job=name, model=model, action_type=action, input_summary=summary)
    db.session.add(job)
    db.session.commit()
    return jsonify(job.to_dict())


def _own_job(job_id):
    return batch.BatchJob.query.filter_by(id=job_id, user_id=current_user.id).first()


@app.route('/api/batches/<int:job_id>/import', methods=['POST'])
@login_required
def import_batch(job_id):
    job = _own_job(job_id)
    if not job:
        return jsonify({'error': 'Not found'}), 404
    if job.status != 'succeeded':
        return jsonify({'error': '完了していないジョブは取り込めません'}), 400
    thought, result = batch.import_job(job)
    return jsonify({'id': job.id, 'thought': thought, 'result': result, 'action': job.action_type})


@app.route('/api/batches/<int:job_id>/cancel', methods=['POST'])
@login_required
def cancel_batch_job(job_id):
    job = _own_job(job_id)
    if not job:
        return jsonify({'error': 'Not found'}), 404
    api_key = current_user.get_api_key()
    if job.status == 'running' and api_key:
        try:
            batch.cancel_batch(api_key, job.provider_job)
        except Exception as e:
            core.logger.warning(f'batch cancel failed: {e}')
        job.status, job.error = 'cancelled', ''
        db.session.commit()
    return jsonify(job.to_dict())


@app.route('/api/batches/<int:job_id>', methods=['DELETE'])
@login_required
def delete_batch_job(job_id):
    job = _own_job(job_id)
    if not job:
        return jsonify({'error': 'Not found'}), 404
    db.session.delete(job)
    db.session.commit()
    return jsonify({'success': True})
