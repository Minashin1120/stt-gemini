"""OpenAI（GPT Transcribe / Whisper / Realtime）のバックグラウンドプロセッサ。"""
import base64
import json
import os
import requests
import threading
import time
import app as core
from app import apply_word_replacements, logger, task_is_cancelled


# --- OpenAI Background Processors ---
def process_openai_gpt_transcribe_background(task_id, api_key, audio_filepath, user_id, action_type, input_summary, prompt="", keywords=None, languages=None, stream=True, model='gpt-transcribe', diarize=False):
    try:
        if task_is_cancelled(task_id):
            return
        url = "https://api.openai.com/v1/audio/transcriptions"

        with open(audio_filepath, 'rb') as f:
            _, ext = os.path.splitext(audio_filepath)
            filename = os.path.basename(audio_filepath)

            files = {'file': (filename, f, 'application/octet-stream')}
            data = {'model': model}
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
            data_list.append(('model', model))
            if diarize:
                data_list.append(('response_format', 'diarized_json'))
                data_list.append(('chunking_strategy', 'auto'))
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

            core.update_task(task_id, phase='sending_to_api')
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
            core.update_task(task_id, status='error', error='OpenAI APIキーが無効です。設定画面で確認してください。')
            return
        elif response.status_code == 413:
            core.update_task(task_id, status='error', error='音声ファイルが最大サイズ(25MB)を超えています。')
            return
        elif response.status_code == 429:
            core.update_task(task_id, status='error', error='OpenAI APIのレート制限に達しました。時間をおいて再度お試しください。')
            return
        elif response.status_code != 200:
            core.update_task(task_id, status='error', error=f'OpenAI Transcription API Error {response.status_code}')
            return

        core.update_task(task_id, phase='receiving')

        if stream:
            full_text = ""
            content_started = False
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
                            if etype == 'transcript.text.delta' and not diarize:
                                delta = event.get('delta', '')
                                if not content_started:
                                    core.update_task(task_id, phase='transcribing')
                                    content_started = True
                                full_text += delta
                                core.update_task(task_id, thought='', result=full_text)
                            elif etype == 'transcript.text.segment' and diarize:
                                if not content_started:
                                    core.update_task(task_id, phase='transcribing')
                                    content_started = True
                                speaker = event.get('speaker', '')
                                segment = event.get('text', '')
                                if segment:
                                    full_text += (f'\n{speaker}: ' if speaker else '\n') + segment
                                    core.update_task(task_id, thought='', result=full_text)
                            elif etype == 'transcript.text.done':
                                if not content_started:
                                    core.update_task(task_id, phase='transcribing')
                                    content_started = True
                                if not diarize or not full_text:
                                    full_text = event.get('text', full_text)
                                core.update_task(task_id, thought='', result=full_text)
                        except json.JSONDecodeError:
                            pass
        else:
            core.update_task(task_id, phase='transcribing')
            result = response.json()
            full_text = result.get('text', '')

        if task_is_cancelled(task_id):
            return

        full_text = apply_word_replacements(user_id, full_text)

        core.update_task(task_id, status='done', thought='', result=full_text)
        core.save_history(user_id, action_type, input_summary, '', full_text)

    except requests.exceptions.Timeout:
        core.update_task(task_id, status='error', error='OpenAI APIへのリクエストがタイムアウトしました。')
    except requests.exceptions.ConnectionError:
        core.update_task(task_id, status='error', error='OpenAI APIへの接続に失敗しました。')
    except Exception as e:
        logger.error(f"OpenAI Transcription background task {task_id} failed: {e}", exc_info=True)
        core.update_task(task_id, status='error', error='OpenAI処理中にエラーが発生しました')


def process_openai_gpt_live_transcribe_background(task_id, api_key, audio_filepath, user_id, action_type, input_summary, model='gpt-live-transcribe'):
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
            core.update_task(task_id, status='error', error='音声変換に失敗しました。ffmpegが必要です。')
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
                            "transcription": {"model": model},
                            "turn_detection": None,
                        }
                    }
                }
            }
            ws.send(json.dumps(session_update))
            core.update_task(task_id, phase='receiving')
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
                    core.update_task(task_id, phase='transcribing', thought='', result=full_transcript)
                elif etype == 'conversation.item.input_audio_transcription.completed':
                    transcript = event.get('transcript', '')
                    if transcript:
                        full_transcript = transcript
                        core.update_task(task_id, phase='transcribing', thought='', result=full_transcript)
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

        ws_url = "wss://api.openai.com/v1/realtime?model=" + model
        core.update_task(task_id, phase='sending_to_api')
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
            core.update_task(task_id, status='error', error=f'OpenAI Realtimeエラー: {error_message[0]}')
            return

        if task_is_cancelled(task_id):
            return

        text = apply_word_replacements(user_id, full_transcript)

        core.update_task(task_id, status='done', thought='', result=text)
        core.save_history(user_id, action_type, input_summary, '', text)

    except Exception as e:
        logger.error(f"OpenAI Live Transcribe background task {task_id} failed: {e}", exc_info=True)
        core.update_task(task_id, status='error', error='OpenAI Live Transcribe処理中にエラーが発生しました')
