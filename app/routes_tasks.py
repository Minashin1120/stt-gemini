"""タスク一覧・キャンセル・SSE 再接続のルート。"""
from flask import jsonify
from flask_login import current_user
from flask_login import login_required
import os
import app as core
from app import app, cancel_task, logger, process_is_alive
from streaming import create_stream_response, stream_task_updates


# --- Task Recovery APIs (Redis-backed) ---
TASK_EXEMPT_ENDPOINTS = {'api.task_stream'}

@app.route('/api/tasks')
@login_required
def list_tasks():
    try:
        task_ids = core.redis_client.smembers(f"user:{current_user.id}:tasks") or set()
        tasks = []
        for tid in list(task_ids):
            task = core.get_task(tid)
            if task:
                if task.get('status') == 'running':
                    active_task_id = core.redis_client.get(f"user:{current_user.id}:active_task")
                    same_server_instance = task.get('server_instance') == str(os.getppid())
                    worker_is_alive = process_is_alive(task.get('worker_pid'))
                    if not same_server_instance or not worker_is_alive or active_task_id != tid:
                        cancel_task(
                            tid,
                            current_user.id,
                            'サービス再起動により処理が中断されました。再度実行してください。',
                        )
                        task = core.get_task(tid)
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
                core.redis_client.srem(f"user:{current_user.id}:tasks", tid)
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
    task = core.get_task(task_id)
    if not task:
        return jsonify({'error': 'Task not found'}), 404
    if task.get('user_id') != str(current_user.id):
        return jsonify({'error': 'Access denied'}), 403
    return create_stream_response(stream_task_updates(task_id), task_id)
