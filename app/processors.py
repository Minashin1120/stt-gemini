"""Gemini 文字起こし / Gemini Live / Grok STT のバックグラウンドプロセッサ。"""
import base64
import json
import os
import requests
import threading
import app as core
from app import apply_word_replacements, get_audio_metadata, logger, task_is_cancelled


# --- Gemini Transcription Processors ---
def process_gemini_transcribe_background(task_id, api_key, audio_filepath, user_id, action_type, input_summary):
    """Gemini Transcribe dedicated model uses Files + Interactions APIs."""
    try:
        if task_is_cancelled(task_id): return
        mime = get_audio_metadata(os.path.basename(audio_filepath))[1] or 'audio/mpeg'
        size = os.path.getsize(audio_filepath)
        core.update_task(task_id, phase='sending_to_api')
        start = requests.post('https://generativelanguage.googleapis.com/upload/v1beta/files',
            headers={'x-goog-api-key': api_key, 'X-Goog-Upload-Protocol': 'resumable',
                'X-Goog-Upload-Command': 'start', 'X-Goog-Upload-Header-Content-Length': str(size),
                'X-Goog-Upload-Header-Content-Type': mime, 'Content-Type': 'application/json'},
            json={'file': {'display_name': os.path.basename(audio_filepath)}}, timeout=(10, 60))
        if start.status_code not in (200, 201):
            core.update_task(task_id, status='error', error=f'Gemini Files API Error {start.status_code}'); return
        upload_url = start.headers.get('X-Goog-Upload-URL')
        if not upload_url:
            core.update_task(task_id, status='error', error='Gemini Files APIのアップロードURLを取得できませんでした'); return
        with open(audio_filepath, 'rb') as audio:
            uploaded = requests.post(upload_url, headers={'x-goog-api-key': api_key,
                'X-Goog-Upload-Offset': '0', 'X-Goog-Upload-Command': 'upload, finalize',
                'Content-Length': str(size)}, data=audio, timeout=(10, 600))
        if uploaded.status_code not in (200, 201):
            core.update_task(task_id, status='error', error=f'Gemini音声アップロードエラー {uploaded.status_code}'); return
        file_uri = uploaded.json().get('file', {}).get('uri')
        if not file_uri:
            core.update_task(task_id, status='error', error='Gemini Files APIの応答に音声URIがありません'); return
        if task_is_cancelled(task_id): return
        core.update_task(task_id, phase='transcribing')
        response = requests.post('https://generativelanguage.googleapis.com/v1beta/interactions',
            headers={'x-goog-api-key': api_key, 'Content-Type': 'application/json'},
            json={'model': 'gemini-3.5-transcribe', 'input': [{'type': 'audio', 'uri': file_uri, 'mime_type': mime}]},
            timeout=(10, 600))
        if response.status_code != 200:
            core.update_task(task_id, status='error', error=f'Gemini Transcribe API Error {response.status_code}'); return
        obj = response.json()
        text = obj.get('output_text', '') or ''.join(part.get('text', '') for item in obj.get('outputs', [])
            for part in item.get('content', []) if part.get('type') == 'text')
        text = apply_word_replacements(user_id, text)
        core.update_task(task_id, status='done', thought='', result=text)
        core.save_history(user_id, action_type, input_summary, '', text)
    except Exception as e:
        logger.error(f"Gemini Transcribe task {task_id} failed: {e}", exc_info=True)
        core.update_task(task_id, status='error', error='Gemini Transcribe処理中にエラーが発生しました')


def process_gemini_live_transcribe_background(task_id, api_key, audio_filepath, user_id, action_type, input_summary):
    """Stream an existing recording through the dedicated Gemini Live transcription model."""
    try:
        import subprocess
        import websocket as ws_client
        if task_is_cancelled(task_id): return
        pcm_path = audio_filepath + '.gemini.pcm'
        subprocess.run(['ffmpeg', '-y', '-i', audio_filepath, '-ar', '16000', '-ac', '1', '-f', 's16le', pcm_path],
            capture_output=True, timeout=120, check=True)
        with open(pcm_path, 'rb') as f: pcm = f.read()
        try: os.remove(pcm_path)
        except OSError: pass
        text_parts, errors = [], []
        ready, done = threading.Event(), threading.Event()
        def on_open(ws):
            ws.send(json.dumps({'setup': {'model': 'models/gemini-3.5-transcribe-live',
                'generationConfig': {'responseModalities': ['TEXT'], 'inputAudioTranscription': {}}}}))
        def on_message(ws, message):
            try:
                event = json.loads(message)
                if event.get('setupComplete') is not None:
                    ready.set(); core.update_task(task_id, phase='receiving')
                    for off in range(0, len(pcm), 32000):
                        if task_is_cancelled(task_id): break
                        ws.send(json.dumps({'realtimeInput': {'audio': {'data': base64.b64encode(pcm[off:off+32000]).decode(), 'mimeType': 'audio/pcm;rate=16000'}}}))
                    ws.send(json.dumps({'realtimeInput': {'audioStreamEnd': True}}))
                server = event.get('serverContent') or {}
                transcript = server.get('inputTranscription') or {}
                if transcript.get('text'):
                    text_parts.append(transcript['text'])
                    core.update_task(task_id, phase='transcribing', thought='', result=''.join(text_parts))
                if server.get('turnComplete'): done.set()
                if event.get('error'):
                    errors.append(event['error'].get('message', 'API error')); ready.set(); done.set()
            except Exception as e:
                errors.append(str(e)); ready.set(); done.set()
        def on_error(ws, error): errors.append(str(error)); ready.set(); done.set()
        core.update_task(task_id, phase='sending_to_api')
        ws = ws_client.WebSocketApp('wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=' + api_key,
            on_open=on_open, on_message=on_message, on_error=on_error)
        threading.Thread(target=ws.run_forever, kwargs={'sslopt': {'check_hostname': True}}, daemon=True).start()
        if not ready.wait(30): raise RuntimeError('Gemini Live接続がタイムアウトしました')
        done.wait(300); ws.close()
        if errors: raise RuntimeError(errors[0])
        if task_is_cancelled(task_id): return
        text = apply_word_replacements(user_id, ''.join(text_parts))
        core.update_task(task_id, status='done', thought='', result=text)
        core.save_history(user_id, action_type, input_summary, '', text)
    except Exception as e:
        logger.error(f"Gemini Live Transcribe task {task_id} failed: {e}", exc_info=True)
        core.update_task(task_id, status='error', error='Gemini Live Transcribe処理中にエラーが発生しました')



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

            core.update_task(task_id, phase='sending_to_api')
            response = requests.post(
                url,
                headers={"Authorization": f"Bearer {api_key}"},
                data={'model': 'grok-voice-transcribe-2.0'},
                files=files,
                timeout=(10, 600)
            )

        if task_is_cancelled(task_id):
            return
        response_error = ''
        try:
            response_error = str(response.json().get('error', ''))
        except (ValueError, AttributeError):
            pass
        if response.status_code in (401, 403) or (
            response.status_code == 400 and 'api key' in response_error.lower()
        ):
            core.update_task(task_id, status='error', error='xAI APIキーが無効です。設定画面で確認してください。')
            return
        elif response.status_code == 413:
            core.update_task(task_id, status='error', error='音声ファイルが最大サイズ(500MB)を超えています。')
            return
        elif response.status_code == 429:
            core.update_task(task_id, status='error', error='xAI APIのレート制限に達しました。時間をおいて再度お試しください。')
            return
        elif response.status_code != 200:
            core.update_task(task_id, status='error', error=f'xAI STT API Error {response.status_code}')
            return

        core.update_task(task_id, phase='receiving')

        result = response.json()
        text = result.get('text', '')
        core.update_task(task_id, phase='transcribing')

        text = apply_word_replacements(user_id, text)

        if task_is_cancelled(task_id):
            return
        core.update_task(task_id, status='done', thought='', result=text)
        core.save_history(user_id, action_type, input_summary, '', text)

    except requests.exceptions.Timeout:
        core.update_task(task_id, status='error', error='xAI STT APIへのリクエストがタイムアウトしました。')
    except requests.exceptions.ConnectionError:
        core.update_task(task_id, status='error', error='xAI STT APIへの接続に失敗しました。')
    except Exception as e:
        logger.error(f"Grok STT background task {task_id} failed: {e}", exc_info=True)
        core.update_task(task_id, status='error', error='xAI STT処理中にエラーが発生しました')
