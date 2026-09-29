"""単語セット / 単語 / 読み仮名生成のルート。"""
from datetime import datetime
from flask import Response
from flask import jsonify
from flask import render_template
from flask import request
from flask_login import current_user
from flask_login import login_required
import json
import requests
import app as core
from app import ALLOWED_MODELS, MAX_WORD_LIST_IMPORT_BYTES, MAX_WORD_LIST_IMPORT_SETS, MAX_WORD_LIST_IMPORT_WORDS, Word, WordSet, app, db, logger, validate_model


# --- Word List Routes ---
def parse_word_list_import(raw_bytes):
    if len(raw_bytes) > MAX_WORD_LIST_IMPORT_BYTES:
        raise ValueError('ファイルサイズは1MB以下にしてください。')
    try:
        payload = json.loads(raw_bytes.decode('utf-8-sig'))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise ValueError('JSONファイルの形式が正しくありません。')

    if (not isinstance(payload, dict) or payload.get('format') != 'voxcribe-word-lists'
            or type(payload.get('version')) is not int or payload.get('version') != 1):
        raise ValueError('Voxcribeの単語リストファイルではありません。')
    word_sets = payload.get('word_sets')
    if not isinstance(word_sets, list):
        raise ValueError('単語セットの形式が正しくありません。')
    if len(word_sets) > MAX_WORD_LIST_IMPORT_SETS:
        raise ValueError(f'単語セットは{MAX_WORD_LIST_IMPORT_SETS}件以下にしてください。')

    parsed_sets = []
    total_words = 0
    for item in word_sets:
        if not isinstance(item, dict):
            raise ValueError('単語セットの形式が正しくありません。')
        name = item.get('name')
        is_active = item.get('is_active')
        words = item.get('words')
        if not isinstance(name, str) or not name.strip() or len(name.strip()) > 100:
            raise ValueError('セット名は1〜100文字で指定してください。')
        if not isinstance(is_active, bool) or not isinstance(words, list):
            raise ValueError('単語セットの形式が正しくありません。')
        total_words += len(words)
        if total_words > MAX_WORD_LIST_IMPORT_WORDS:
            raise ValueError(f'単語は合計{MAX_WORD_LIST_IMPORT_WORDS}件以下にしてください。')

        parsed_words = []
        for word in words:
            if not isinstance(word, dict):
                raise ValueError('単語の形式が正しくありません。')
            reading = word.get('reading')
            replacement = word.get('replacement')
            if (not isinstance(reading, str) or not reading.strip() or len(reading.strip()) > 255
                    or not isinstance(replacement, str) or not replacement.strip() or len(replacement.strip()) > 255):
                raise ValueError('読みと変換後はそれぞれ1〜255文字で指定してください。')
            parsed_words.append({'reading': reading.strip(), 'replacement': replacement.strip()})
        parsed_sets.append({'name': name.strip(), 'is_active': is_active, 'words': parsed_words})
    return parsed_sets, total_words


@app.route('/api/word_sets/export')
@login_required
def export_word_sets():
    sets = WordSet.query.filter_by(user_id=current_user.id).order_by(WordSet.id.asc()).all()
    payload = {
        'format': 'voxcribe-word-lists',
        'version': 1,
        'word_sets': [
            {
                'name': word_set.name,
                'is_active': bool(word_set.is_active),
                'words': [
                    {'reading': word.reading, 'replacement': word.replacement}
                    for word in Word.query.filter_by(set_id=word_set.id).order_by(Word.id.asc()).all()
                ],
            }
            for word_set in sets
        ],
    }
    filename = f"voxcribe-word-lists-{datetime.now().strftime('%Y%m%d')}.json"
    response = Response(json.dumps(payload, ensure_ascii=False, indent=2) + '\n', mimetype='application/json')
    response.headers['Content-Disposition'] = f'attachment; filename="{filename}"'
    response.headers['Cache-Control'] = 'no-store'
    return response


@app.route('/api/word_sets/import', methods=['POST'])
@login_required
def import_word_sets():
    upload = request.files.get('file')
    if not upload or not upload.filename:
        return jsonify({'error': 'インポートするJSONファイルを選択してください。'}), 400
    raw_bytes = upload.stream.read(MAX_WORD_LIST_IMPORT_BYTES + 1)
    try:
        parsed_sets, total_words = parse_word_list_import(raw_bytes)
        for item in parsed_sets:
            word_set = WordSet(user_id=current_user.id, name=item['name'], is_active=item['is_active'])
            db.session.add(word_set)
            db.session.flush()
            db.session.add_all([
                Word(set_id=word_set.id, reading=word['reading'], replacement=word['replacement'])
                for word in item['words']
            ])
        db.session.commit()
    except ValueError as error:
        db.session.rollback()
        return jsonify({'error': str(error)}), 400
    except Exception:
        db.session.rollback()
        logger.exception('Word list import failed for user %s', current_user.id)
        return jsonify({'error': '単語リストのインポートに失敗しました。'}), 500
    return jsonify({'success': True, 'sets': len(parsed_sets), 'words': total_words})


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

@app.route('/api/yomigana/generate', methods=['POST'])
@login_required
def generate_yomigana():
    word = (request.form.get('word') or '').strip()
    model = request.form.get('model', 'gemini-3.5-flash')
    if not word:
        return jsonify({'error': 'Word is required'}), 400
    model = validate_model(model)
    if model not in ALLOWED_MODELS or not model.startswith('gemini-'):
        model = 'gemini-3.5-flash'
    api_key = current_user.get_api_key()
    if not api_key:
        return jsonify({'error': 'Gemini API key not configured'}), 400
    prompt = f"次の単語の読み方をひらがな（スペースなし）で答えてください。読み方だけを出力し、他の文章は含めないでください。\n単語: {word}"
    try:
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
        response = requests.post(
            url,
            headers={'Content-Type': 'application/json', 'x-goog-api-key': api_key},
            json={'contents': [{'parts': [{'text': prompt}]}]},
            timeout=30
        )
        if response.status_code != 200:
            detail = ''
            try:
                detail = response.json().get('error', {}).get('message', '')
            except Exception:
                pass
            return jsonify({'error': f'API Error: {response.status_code}' + (f' ({detail})' if detail else '')}), 500
        data = response.json()
        reading = data['candidates'][0]['content']['parts'][0]['text'].strip()
        return jsonify({'reading': reading})
    except Exception as e:
        logger.error(f"Yomigana generation failed: {e}", exc_info=True)
        return jsonify({'error': '生成に失敗しました'}), 500

@app.route('/api/words/delete/<int:word_id>', methods=['POST'])
@login_required
def delete_word(word_id):
    w = Word.query.join(WordSet).filter(Word.id == word_id, WordSet.user_id == current_user.id).first()
    if w:
        db.session.delete(w)
        db.session.commit()
        return jsonify({'success': True})
    return jsonify({'error': 'Not found'}), 404
