// APIキー未設定モーダル（キー入力・モデル切替・録音破棄確認）。
// --- API Key Modal ---
let _resolveApiKeyPrompt = null;
let _akAudioBlob = null;
let _akAudioName = null;
let _akIsRecording = false;
let _akOriginalModel = null;

function getApiKeyType(model) {
    if (model.startsWith('gpt-') || model === 'whisper-1') return 'openai';
    return (model === 'grok-stt' || model === 'grok-live-transcribe') ? 'xai' : 'gemini';
}

function showView(viewId) {
    ['akViewInput', 'akViewSwitch', 'akViewDiscard'].forEach(id => {
        const el = document.getElementById(id);
        if (id === viewId) {
            el.style.display = '';
            el.classList.remove('ak-animate-in');
            void el.offsetWidth;
            el.classList.add('ak-animate-in');
        } else {
            el.style.display = 'none';
        }
    });
}

function showApiKeyModal(keyType, model) {
    const overlay = document.getElementById('apiKeyOverlay');
    const titleEl = document.getElementById('akTitle');
    const msgEl = document.getElementById('akMessage');
    const inputEl = document.getElementById('akInput');
    const errorEl = document.getElementById('akError');
    const saveBtn = document.getElementById('akBtnSave');

    showView('akViewInput');

    errorEl.style.display = 'none';
    inputEl.value = '';
    inputEl.focus();

    if (keyType === 'xai') {
        titleEl.textContent = 'xAI (Grok) APIキーの設定';
        msgEl.textContent = 'Grok STT モデルを使用するには xAI API キーが必要です。';
        inputEl.placeholder = 'xai-...';
    } else if (keyType === 'openai') {
        titleEl.textContent = 'OpenAI APIキーの設定';
        msgEl.textContent = 'GPT-Transcribe / GPT-Live / Whisperモデルを使用するには OpenAI API キーが必要です。';
        inputEl.placeholder = 'sk-...';
    } else {
        titleEl.textContent = 'Gemini APIキーの設定';
        msgEl.textContent = 'Gemini モデルを使用するには Gemini API キーが必要です。';
        inputEl.placeholder = 'AIzaSy...';
    }

    // Enter key support
    inputEl.onkeydown = (e) => {
        if (e.key === 'Enter') {
            e.preventDefault();
            saveBtn.click();
        }
    };

    // Save button
    saveBtn.onclick = async () => {
        const apiKey = inputEl.value.trim();
        if (!apiKey) {
            errorEl.textContent = 'APIキーを入力してください';
            errorEl.style.display = 'block';
            return;
        }
        saveBtn.disabled = true;
        saveBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>保存中...';
        try {
            const r = await csrfFetch('/api/save_api_key', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ type: keyType, api_key: apiKey })
            });
            const data = await r.json();
            if (!r.ok) {
                errorEl.textContent = data.error || '保存に失敗しました';
                errorEl.style.display = 'block';
                saveBtn.disabled = false;
                saveBtn.textContent = '保存して送信';
                return;
            }
            closeApiKeyModal();
            if (_resolveApiKeyPrompt) _resolveApiKeyPrompt({ proceed: true, model: model });
        } catch (e) {
            errorEl.textContent = '通信エラーが発生しました';
            errorEl.style.display = 'block';
            saveBtn.disabled = false;
            saveBtn.textContent = '保存して送信';
        }
    };

    // Switch model button -> show model selection
    document.getElementById('akBtnSwitch').onclick = () => showSwitchView(model);

    // Close button
    document.getElementById('akBtnClose').onclick = () => handleClose();
    document.getElementById('akBtnCloseSwitch').onclick = () => handleClose();

    // Click outside to close
    overlay.onclick = (e) => {
        if (e.target === overlay) handleClose();
    };

    // Escape key
    overlay.onkeydown = (e) => {
        if (e.key === 'Escape') handleClose();
    };

    overlay.classList.add('show');
    inputEl.focus();

    // Liquid Glass pseudo-refraction: mouse tracking for dynamic glow
    const modal = document.querySelector('.api-key-modal');
    const onMouseMove = (e) => {
        const rect = modal.getBoundingClientRect();
        const x = ((e.clientX - rect.left) / rect.width * 100).toFixed(1);
        const y = ((e.clientY - rect.top) / rect.height * 100).toFixed(1);
        modal.style.setProperty('--ak-glow-x', x + '%');
        modal.style.setProperty('--ak-glow-y', y + '%');
    };
    modal._akMouseMove = onMouseMove;
    overlay.addEventListener('mousemove', onMouseMove);
}

function handleClose() {
    if (_akIsRecording) {
        showView('akViewDiscard');
    } else {
        closeApiKeyModal();
        if (_resolveApiKeyPrompt) _resolveApiKeyPrompt({ proceed: false });
    }
}

async function showSwitchView(currentModel) {
    showView('akViewSwitch');

    const listEl = document.getElementById('akModelList');
    const noModelsEl = document.getElementById('akNoModels');

    listEl.innerHTML = '<div class="text-center py-2"><span class="spinner-border spinner-border-sm me-1"></span>確認中...</div>';
    noModelsEl.style.display = 'none';

    try {
        const r = await csrfFetch('/api/check_api_keys');
        const data = await r.json();

        const models = [];
        if (data.has_gemini_key) {
            models.push(
                { label: '3.6 Flash', value: 'gemini-3.6-flash' },
                { label: '3.5 Flash', value: 'gemini-3.5-flash' },
                { label: '3.5 Flash-Lite', value: 'gemini-3.5-flash-lite' },
                { label: '3.0 Flash', value: 'gemini-3-flash-preview' },
                { label: '3.1 Flash-Lite', value: 'gemini-3.1-flash-lite' },
                { label: 'Gemini Transcribe', value: 'gemini-3.5-transcribe' },
                { label: 'Gemini Live Transcribe', value: 'gemini-3.5-transcribe-live' }
            );
        }
        if (data.has_openai_key) {
            models.push(
                { label: 'GPT-Transcribe', value: 'gpt-transcribe' },
                { label: 'GPT-Live Transcribe', value: 'gpt-live-transcribe' },
                { label: 'Whisper', value: 'whisper-1' },
                { label: 'GPT-4o Transcribe', value: 'gpt-4o-transcribe' },
                { label: 'GPT-4o Mini Transcribe', value: 'gpt-4o-mini-transcribe' },
                { label: 'GPT-4o Speaker Transcribe', value: 'gpt-4o-transcribe-diarize' },
                { label: 'GPT-Realtime-Whisper', value: 'gpt-realtime-whisper' }
            );
        }
        if (data.has_xai_key) {
            models.push(
                { label: 'Grok STT', value: 'grok-stt' },
                { label: 'Grok Live', value: 'grok-live-transcribe' }
            );
        }

        if (models.length === 0) {
            listEl.innerHTML = '';
            noModelsEl.style.display = 'block';
            return;
        }

        // Filter out current model
        const available = models.filter(m => m.value !== currentModel);
        if (available.length === 0) {
            listEl.innerHTML = '<div class="text-muted small">現在のモデル以外に利用可能なモデルはありません。</div>';
            return;
        }

        listEl.innerHTML = available.map(m => `
            <button class="btn btn-outline-primary text-start model-option" data-model="${m.value}">
                ${m.label}
            </button>
        `).join('');

        listEl.querySelectorAll('.model-option').forEach(btn => {
            btn.onclick = () => {
                const model = btn.dataset.model;
                setModel(model);
                closeApiKeyModal();
                if (_resolveApiKeyPrompt) _resolveApiKeyPrompt({ proceed: true, model: model });
            };
        });

    } catch (e) {
        listEl.innerHTML = '<div class="text-danger small">読み込みに失敗しました</div>';
    }

    document.getElementById('akBtnBackSwitch').onclick = () => {
        showView('akViewInput');
        document.getElementById('akInput').focus();
    };
}

function closeApiKeyModal() {
    const overlay = document.getElementById('apiKeyOverlay');
    overlay.classList.remove('show');
    overlay.onkeydown = null;
    const modal = document.querySelector('.api-key-modal');
    if (modal && modal._akMouseMove) {
        overlay.removeEventListener('mousemove', modal._akMouseMove);
        delete modal._akMouseMove;
    }
    const saveBtn = document.getElementById('akBtnSave');
    saveBtn.disabled = false;
    saveBtn.textContent = '保存して送信';
    showView('akViewInput');
    _akAudioBlob = null;
    _akAudioName = null;
    _akIsRecording = false;
    _akOriginalModel = null;
}

function ensureApiKeyForModel(model, isRecording, audioBlob, audioName) {
    return new Promise((resolve) => {
        csrfFetch('/api/check_api_key', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ model: model })
        }).then(r => r.json()).then(data => {
            if (data.has_key) {
                resolve({ proceed: true, model: model });
                return;
            }
            const keyType = getApiKeyType(model);
            _resolveApiKeyPrompt = resolve;
            _akAudioBlob = audioBlob || null;
            _akAudioName = audioName || null;
            _akIsRecording = !!isRecording;
            _akOriginalModel = model;
            showApiKeyModal(keyType, model);
        }).catch(() => {
            resolve({ proceed: true, model: model });
        });
    });
}

// Discard view buttons
document.addEventListener('DOMContentLoaded', () => {
    document.getElementById('akBtnBackDiscard').onclick = () => {
        showView('akViewInput');
        document.getElementById('akInput').focus();
    };
    document.getElementById('akBtnConfirmDiscard').onclick = () => {
        closeApiKeyModal();
        if (_resolveApiKeyPrompt) _resolveApiKeyPrompt({ proceed: false });
    };
    document.getElementById('akBtnDownload').onclick = () => {
        if (_akAudioBlob) {
            const a = document.createElement('a');
            a.href = URL.createObjectURL(_akAudioBlob);
            a.download = _akAudioName || 'recording.mp3';
            a.click();
            URL.revokeObjectURL(a.href);
        }
    };
});
