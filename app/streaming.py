"""Gemini 汎用バックグラウンド処理と、Redis を見て進捗を流す SSE ジェネレータ。"""
from flask import Response
from flask import stream_with_context
import json
import requests
import time
import app as core
from app import logger, task_is_cancelled


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
                core.update_task(task_id, phase='sending_to_api')
                response = requests.post(
                    url,
                    headers={'Content-Type': 'application/json', 'x-goog-api-key': api_key},
                    json=payload,
                    stream=True,
                    timeout=(10, 600),
                )
                
                if response.status_code == 429 and attempt < max_retries:
                    core.update_task(task_id, status='running')
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
                        core.update_task(task_id, status='error', error='API Error 429: リクエスト制限に達しました。時間をおいて再度お試しください。')
                    else:
                        core.update_task(task_id, status='error', error=f'API Error {response.status_code}')
                    return

                core.update_task(task_id, phase='receiving')
                text_started = False
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
                                        core.update_task(task_id, thought=full_thought, result=full_text)
                                    elif 'text' in p:
                                        content = p['text']
                                        if not text_started:
                                            core.update_task(task_id, phase='transcribing')
                                            text_started = True
                                        full_text += content
                                        core.update_task(task_id, thought=full_thought, result=full_text)
                            except:
                                pass
                break
            except Exception as e:
                had_error = True
                logger.error(f"Gemini API request failed for task {task_id}: {e}", exc_info=True)
                core.update_task(task_id, status='error', error='APIリクエスト中にエラーが発生しました')
                return
        
        if had_error or task_is_cancelled(task_id):
            return
        
        core.save_history(user_id, action_type, input_summary, full_thought, full_text)
        core.update_task(task_id, status='done', thought=full_thought, result=full_text)
    except Exception as e:
        logger.error(f"Background task {task_id} failed: {e}", exc_info=True)
        core.update_task(task_id, status='error', error='処理中にエラーが発生しました')

# --- SSE Generator that polls Redis for task progress ---
# 各フェーズの表示メッセージ。バックグラウンド処理が core.update_task(phase=...) で切り替える。
PHASE_LABELS = {
    'sending_to_api': 'APIサーバーに送信中...',
    'receiving': '解析中...',
    'transcribing': '書き起こし中...',
}

def stream_task_updates(task_id):
    last_thought = ""
    last_text = ""
    last_phase = ""
    while True:
        task = core.get_task(task_id)
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
