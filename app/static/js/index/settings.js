// モデル・推論レベル・後処理設定の保存/復元、カスタムドロップダウン、ファイル選択。

let currentModel = 'gemini-3.6-flash';
const noThinkModels = ['grok-stt', 'grok-live-transcribe', 'gpt-transcribe', 'gpt-live-transcribe', 'whisper-1', 'gpt-4o-transcribe', 'gpt-4o-mini-transcribe', 'gpt-4o-transcribe-diarize', 'gpt-realtime-whisper', 'gemini-3.5-transcribe', 'gemini-3.5-transcribe-live'];

function getModel() {
    return currentModel;
}

function setModel(value) {
    // Grok Liveのリアルタイムセッション中にモデルを切り替えると、開いたままのWebSocketが
    // 停止処理から見えなくなり(wasGrokLiveの判定がgetModel()依存のため)、孤立してしまう。
    if (grokLiveWs && isRecording) {
        showToast('ライブ文字起こし中はモデルを変更できません。録音を停止してから変更してください', true);
        return;
    }
    localStorage.setItem('stt_t_' + currentModel, el.think.value);
    localStorage.setItem('stt_m', value);
    currentModel = value;
    const textEl = document.querySelector('#modelSelect .custom-select-text');
    const opt = document.querySelector(`#modelSelect .custom-select-option[data-value="${value}"]`);
    if (textEl && opt) textEl.textContent = opt.textContent;
    syncModelDeprecationInfo(value);
    syncBatchToggle();
    const savedThink = localStorage.getItem('stt_t_' + currentModel);
    if (savedThink) el.think.value = savedThink;
    updatePostprocessBar();
    const noThinkModels = ['grok-stt', 'grok-live-transcribe', 'gpt-transcribe', 'gpt-live-transcribe', 'whisper-1', 'gpt-4o-transcribe', 'gpt-4o-mini-transcribe', 'gpt-4o-transcribe-diarize', 'gpt-realtime-whisper', 'gemini-3.5-transcribe', 'gemini-3.5-transcribe-live'];
    if (noThinkModels.includes(value)) {
        if (thinkingOpen) thoughtCol.hide();
        el.think.disabled = true;
        el.think.title = (value === 'grok-stt' || value === 'grok-live-transcribe') ? 'Grok STTでは推論レベルは使用されません' : 'OpenAIモデルでは推論レベルは使用されません';
    } else {
        el.think.disabled = false;
        el.think.title = '';
    }
    syncThinkDropdown();
}

function syncModelDeprecationInfo(value) {
    const info = document.getElementById('modelDeprecatedInfo');
    if (info) info.classList.toggle('d-none', !['gpt-4o-transcribe', 'gpt-4o-mini-transcribe', 'gpt-4o-transcribe-diarize'].includes(value));
}

function syncThinkDropdown() {
    const sel = document.getElementById('thinkingLevelSelect');
    const textEl = sel.querySelector('.custom-select-text');
    const opt = sel.querySelector(`.custom-select-option[data-value="${el.think.value}"]`);
    if (textEl && opt) textEl.textContent = opt.textContent;
    const toggle = sel.querySelector('.custom-select-toggle');
    if (el.think.disabled) {
        toggle.setAttribute('disabled', '');
        toggle.style.opacity = '.65';
        toggle.style.cursor = 'not-allowed';
    } else {
        toggle.removeAttribute('disabled');
        toggle.style.opacity = '';
        toggle.style.cursor = '';
    }
    toggle.title = el.think.title || '';
}

function saveSet(){ 
    localStorage.setItem('stt_t_' + getModel(), el.think.value);
    if(el.noise) localStorage.setItem('stt_n', el.noise.checked);
    if(el.rephrase) localStorage.setItem('stt_r', el.rephrase.checked);
    if(el.filler) localStorage.setItem('stt_fl', el.filler.checked);
    const fmt = document.querySelector('input[name="format"]:checked');
    if(fmt) localStorage.setItem('stt_f', fmt.value);
    localStorage.setItem('stt_m', getModel());
}

function updatePostprocessBar() {
    const isLite = currentModel === 'gemini-3.1-flash-lite' || currentModel === 'gemini-3.5-flash-lite';
    const bar = document.querySelector('.result-postprocess-bar');
    if (bar) bar.classList.toggle('d-none', !isLite);
}

// Custom dropdown event handlers
function positionDropdown(id) {
    const sel = document.getElementById(id);
    const toggle = sel.querySelector('.custom-select-toggle');
    const menu = sel.querySelector('.custom-select-menu');
    const rect = toggle.getBoundingClientRect();
    menu.style.left = rect.left + 'px';
    menu.style.width = rect.width + 'px';
    menu.style.top = (rect.bottom + 4) + 'px';
}

function toggleCustomDropdown(id) {
    const sel = document.getElementById(id);
    const wasOpen = sel.classList.contains('open');
    document.querySelectorAll('.custom-select.open').forEach(s => s.classList.remove('open'));
    if (!wasOpen) {
        sel.classList.add('open');
        positionDropdown(id);
    }
}

function closeAllDropdowns() {
    document.querySelectorAll('.custom-select.open').forEach(s => s.classList.remove('open'));
}

// --- File Upload UI Logic ---
function handleFileSelect(file) {
    if (!file) return;
    if (!file.type.startsWith('audio/') && !file.name.toLowerCase().endsWith('.mp3') && !file.name.toLowerCase().endsWith('.wav') && !file.name.toLowerCase().endsWith('.m4a')) {
        showToast("音声ファイルを選択してください", true);
        return;
    }
    hideUploadProgress();
    hideErrorDownloadButton();
    selectedFile = file;
    rememberLocalAudio(file, file.name);
    fileNameEl.innerText = file.name;
    fileSizeEl.innerText = (file.size / (1024 * 1024)).toFixed(2) + " MB";
    fileInfo.classList.remove('d-none');
    btnUploadNew.disabled = false;
    btnUploadAppend.disabled = false;
    el.stat.innerText = "ファイルが選択されました";
}

function initSettings() {
        const n=localStorage.getItem('stt_n');
    const rephraseSaved=localStorage.getItem('stt_r');
    const fillerSaved=localStorage.getItem('stt_fl');
    const f=localStorage.getItem('stt_f');
    const m=localStorage.getItem('stt_m');
    if(n!==null && el.noise) el.noise.checked=(n==='true');
    if(rephraseSaved!==null && el.rephrase) el.rephrase.checked=(rephraseSaved==='true');
    if(fillerSaved!==null && el.filler) el.filler.checked=(fillerSaved==='true');
    if(f) { const r = document.querySelector(`input[name="format"][value="${f}"]`); if(r) r.checked=true; }
    if (m) {
        const opt = document.querySelector(`#modelSelect .custom-select-option[data-value="${m}"]`);
        if (opt) currentModel = m;
    }
        const textEl = document.querySelector('#modelSelect .custom-select-text');
        const activeOpt = document.querySelector(`#modelSelect .custom-select-option[data-value="${currentModel}"]`);
    if (textEl && activeOpt) textEl.textContent = activeOpt.textContent;
    syncModelDeprecationInfo(currentModel);
        const savedThink = localStorage.getItem('stt_t_' + currentModel);
    if (savedThink) el.think.value = savedThink;
    if (noThinkModels.includes(currentModel)) {
        el.think.disabled = true;
        el.think.title = (currentModel === 'grok-stt' || currentModel === 'grok-live-transcribe') ? 'Grok STTでは推論レベルは使用されません' : 'OpenAIモデルでは推論レベルは使用されません';
        if (thinkingOpen) thoughtCol.hide();
    }
    updatePostprocessBar();
    syncThinkDropdown();
    // サイトアクセス時にマイクを事前取得・検証（録音タブ表示中のみ・失敗時は再試行可能）
    if (!isRecording) {
        const recordTab = document.getElementById('record-tab');
        if (!recordTab || recordTab.classList.contains('active')) prepareMic();
    }
    el.think.addEventListener('change', saveSet);
    if(el.noise) el.noise.addEventListener('change', saveSet);
    if(el.rephrase) el.rephrase.addEventListener('change', saveSet);
    if(el.filler) el.filler.addEventListener('change', saveSet);
    document.querySelectorAll('input[name="format"]').forEach(r=>r.addEventListener('change', saveSet));
    document.querySelectorAll('#modelSelect .custom-select-option').forEach(opt => {
        opt.addEventListener('click', () => {
            setModel(opt.dataset.value);
            closeAllDropdowns();
        });
    });
    document.querySelector('#modelSelect .custom-select-toggle').addEventListener('click', (e) => {
        e.stopPropagation();
        toggleCustomDropdown('modelSelect');
    });
    document.querySelectorAll('#thinkingLevelSelect .custom-select-option').forEach(opt => {
        opt.addEventListener('click', () => {
            el.think.value = opt.dataset.value;
            el.think.dispatchEvent(new Event('change'));
            syncThinkDropdown();
            closeAllDropdowns();
        });
    });
    document.querySelector('#thinkingLevelSelect .custom-select-toggle').addEventListener('click', (e) => {
        e.stopPropagation();
        toggleCustomDropdown('thinkingLevelSelect');
    });
    document.addEventListener('click', closeAllDropdowns);
    window.addEventListener('scroll', () => {
        document.querySelectorAll('.custom-select.open').forEach(s => positionDropdown(s.id));
    }, true);
    window.addEventListener('resize', closeAllDropdowns);
    loadHistory();
    loadWordSetStatus();
    checkRunningTasks();
    dropArea.onclick = () => fileInput.click();
    fileInput.onchange = (e) => handleFileSelect(e.target.files[0]);
    dropArea.ondragover = (e) => { e.preventDefault(); dropArea.classList.add('drag-over'); };
    dropArea.ondragleave = () => dropArea.classList.remove('drag-over');
    dropArea.ondrop = (e) => {
        e.preventDefault();
        dropArea.classList.remove('drag-over');
        handleFileSelect(e.dataTransfer.files[0]);
    };
    removeFileBtn.onclick = (e) => {
        e.stopPropagation();
        selectedFile = null;
        fileInput.value = "";
        fileInfo.classList.add('d-none');
        btnUploadNew.disabled = true;
        btnUploadAppend.disabled = true;
        hideUploadProgress();
        hideErrorDownloadButton();
        lastLocalAudioBlob = null;
        lastLocalAudioName = null;
        el.stat.innerText = "待機中";
    };
}
