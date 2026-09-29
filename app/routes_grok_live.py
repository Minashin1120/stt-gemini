"""Grok Live（WebSocket リアルタイム文字起こし）。"""
from flask import request
from flask_login import current_user
from urllib.parse import urlencode
import json
import threading
import time
import app as core
from app import is_truthy
from app import ActiveTaskError, GROK_LIVE_ALLOWED_ORIGINS, GROK_LIVE_MAX_SECONDS, GROK_LIVE_TASK_REFRESH_SECS, check_user_model_rate_limit, is_plausible_xai_api_key, logger, sock


# --- Grok Live (リアルタイム文字起こし, WebSocket) ---

def _grok_live_validated_params():
    """クライアントからのクエリパラメータをホワイトリストで検証し、xAIへ転送するdictを返す。
    modelは常にこちら側で固定するのでここでは扱わない。"""
    args = request.args
    params = {}

    encoding = args.get('encoding', 'pcm')
    if encoding not in ('pcm', 'mulaw', 'alaw'):
        encoding = 'pcm'
    params['encoding'] = encoding

    try:
        sample_rate = int(args.get('sample_rate', 16000))
    except (TypeError, ValueError):
        sample_rate = 16000
    params['sample_rate'] = max(8000, min(sample_rate, 48000))

    params['interim_results'] = 'true' if is_truthy(args.get('interim_results')) else 'false'

    try:
        endpointing = int(args.get('endpointing', 400))
    except (TypeError, ValueError):
        endpointing = 400
    params['endpointing'] = max(0, min(endpointing, 5000))

    language = (args.get('language') or '').strip()[:16]
    if language:
        params['language'] = language

    params['diarize'] = 'true' if is_truthy(args.get('diarize')) else 'false'
    params['filler_words'] = 'true' if is_truthy(args.get('filler_words')) else 'false'

    multichannel = is_truthy(args.get('multichannel'))
    try:
        channels = int(args.get('channels', 1))
    except (TypeError, ValueError):
        channels = 1
    channels = max(1, min(channels, 8))
    if multichannel and channels < 2:
        multichannel = False
    params['multichannel'] = 'true' if multichannel else 'false'
    params['channels'] = channels

    keyterms = [k.strip()[:50] for k in args.getlist('keyterm') if k.strip()][:100]
    if keyterms:
        params['keyterm'] = keyterms

    return params

@sock.route('/ws/grok_live')
def ws_grok_live(ws):
    # flask-sockはソケットをWebSocketへ昇格させてから本体を実行するため、
    # @login_requiredのリダイレクトは配信できない。ここで手動チェックする。
    if not current_user.is_authenticated:
        ws.close()
        return

    # WebSocketのハンドシェイクはCSRFトークンの対象外(protect_csrfはPOST等のみ検査)かつ
    # Same-Origin Policyの制約も受けないため、Originを自前で検証する(CSWSH対策)。
    if request.headers.get('Origin', '') not in GROK_LIVE_ALLOWED_ORIGINS:
        ws.close()
        return

    if not check_user_model_rate_limit():
        ws.close()
        return

    api_key = current_user.get_xai_api_key()
    if not is_plausible_xai_api_key(api_key):
        try: ws.send(json.dumps({"type": "error", "message": "xAI APIキーが未設定か、形式が正しくありません。設定画面で確認してください。"}))
        except Exception: pass
        ws.close()
        return

    try:
        task_id = core.create_task(current_user.id, "transcribe_live", "Live Audio (Grok)", "grok-live-transcribe")
    except ActiveTaskError:
        try: ws.send(json.dumps({"type": "error", "message": "別の処理が実行中です。完了後に再度お試しください。"}))
        except Exception: pass
        ws.close()
        return

    user_id = current_user.id
    params = _grok_live_validated_params()
    xai_url = "wss://api.x.ai/v1/stt?" + urlencode({**params, "model": "grok-voice-transcribe-2.0"}, doseq=True)

    import websocket as ws_client
    try:
        xai_ws = ws_client.create_connection(
            xai_url, header=[f"Authorization: Bearer {api_key}"], timeout=10
        )
    except Exception as e:
        logger.error(f"Grok Live: xAI接続失敗 task={task_id}: {e}")
        status_code = getattr(e, 'status_code', None)
        message = ('xAI APIキーが無効です。設定画面で確認してください。'
                   if status_code in (400, 401, 403) else 'xAI STTへの接続に失敗しました。')
        try: ws.send(json.dumps({"type": "error", "message": message}))
        except Exception: pass
        core.update_task(task_id, status='error', error=message)
        ws.close()
        return

    stop_event = threading.Event()

    def relay_xai_to_browser():
        """xAI -> ブラウザへの唯一の送信経路(ws.send()を呼ぶのはこのスレッドのみ)。"""
        try:
            while not stop_event.is_set():
                msg = xai_ws.recv()
                if not msg:
                    break
                ws.send(msg)
        except Exception as e:
            try:
                ws.send(json.dumps({"type": "error", "message": str(e)}))
            except Exception:
                pass
        finally:
            stop_event.set()

    relay_thread = threading.Thread(target=relay_xai_to_browser, daemon=True)
    relay_thread.start()

    session_start = time.time()
    last_refresh = session_start
    had_error = False
    try:
        while not stop_event.is_set():
            data = ws.receive(timeout=5)
            if data is not None:
                if isinstance(data, (bytes, bytearray)):
                    xai_ws.send_binary(data)
                else:
                    try:
                        xai_ws.send(data)
                    except Exception:
                        break
                    try:
                        if json.loads(data).get("type") == "audio.done":
                            # audio.doneを転送した後、xAIが最後のtranscript.doneを送って
                            # relay_threadが自然に終わる(=xai_ws.recv()が空を返す)のを少し待つ。
                            # ここで即座にxai_wsを閉じると末尾の文字起こしが失われる。
                            # (短すぎるとspeech_final前に停止した末尾発話の確定が間に合わないため8秒に設定)
                            relay_thread.join(timeout=8)
                            break
                    except (ValueError, TypeError):
                        pass
            elif ws.connected is False:
                break

            now = time.time()
            if now - last_refresh > GROK_LIVE_TASK_REFRESH_SECS:
                core.update_task(task_id, phase='streaming')
                last_refresh = now
            if now - session_start > GROK_LIVE_MAX_SECONDS:
                try: xai_ws.send(json.dumps({"type": "finalize"}))
                except Exception: pass
                relay_thread.join(timeout=8)
                break
    except Exception as e:
        had_error = True
        logger.warning(f"Grok Live: relay error task={task_id}: {e}")
    finally:
        stop_event.set()
        try: xai_ws.close()
        except Exception: pass
        relay_thread.join(timeout=5)
        if had_error:
            core.update_task(task_id, status='error', error='xAI STT接続でエラーが発生しました。')
        else:
            core.update_task(task_id, status='done')
        try: ws.close()
        except Exception: pass
