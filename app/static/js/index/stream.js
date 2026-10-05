// SSE ストリームの受信・表示と、単一/チャンク並列アップロード。

// --- Stream & Upload ---
let streamSession = null;
let CHUNK_THRESHOLD;
let CHUNK_SIZE;

/** 新規モード開始時: 結果テキスト・思考・initText を破棄（録音側と同じ） */
function clearResultUiForNew() {
    el.tho.innerText = "";
    el.res.value = "";
    initText = "";
    el.copy.disabled = true;
    el.re.disabled = true;
    el.delBtn.disabled = true;
    if (el.fix) el.fix.disabled = true;
    if (el.correctRephrase) el.correctRephrase.disabled = true;
}

/** サーバー側で履歴クリア済みの前提で履歴パネルを空表示にする */
function clearHistoryUi() {
    if (el.hist) {
        el.hist.innerHTML = '<div class="text-center text-muted small py-3">履歴なし</div>';
    }
}

function beginStreamSession(taskId = null) {
    streamSession = {
        buffer: "",
        text: "",
        hadError: false,
        wasCancelled: false
    };
    currentTaskId = taskId || currentTaskId;
    el.tho.innerText = "";
    // 解析フェーズへ移行: 進捗バーは隠すが、エラー時DL用の音声は保持
    hideUploadProgress();
    hideErrorDownloadButton();
    if (el.procBar) el.procBar.classList.remove('d-none');

    if (!isAppendMode) {
        // 新規: テキストと履歴表示をクリア（サーバーは is_append=0 で既に履歴・旧音声を削除済み）
        clearResultUiForNew();
        clearHistoryUi();
    } else {
        let cur = el.res.value.trim();
        initText = cur ? cur + "\n\n" : "";
        el.res.value = initText;
    }

    const noThinkModels2 = ['grok-stt', 'grok-live-transcribe', 'gpt-transcribe', 'gpt-live-transcribe', 'whisper-1', 'gpt-4o-transcribe', 'gpt-4o-mini-transcribe', 'gpt-4o-transcribe-diarize', 'gpt-realtime-whisper', 'gemini-3.5-transcribe', 'gemini-3.5-transcribe-live'];
    if (el.think.value !== 'MINIMAL' && !noThinkModels2.includes(getModel())) thoughtCol.show();
    el.abort.classList.remove('d-none');
}

function consumeStreamChunk(chunkText) {
    if (!streamSession || !chunkText) return;
    streamSession.buffer += chunkText;
    const lines = streamSession.buffer.split('\n\n');
    streamSession.buffer = lines.pop();

    lines.forEach(l => {
        if (!l.startsWith('data: ')) return;
        try {
            const d = JSON.parse(l.substring(6));
            if (d.type === 'thought') {
                el.tho.innerText += d.content;
                el.tho.scrollTop = el.tho.scrollHeight;
            } else if (d.type === 'text') {
                streamSession.text += d.content;
                el.res.value = initText + streamSession.text;
                el.res.scrollTop = el.res.scrollHeight;
            } else if (d.type === 'status') {
                if (el.stat && d.content) el.stat.innerText = d.content;
            } else if (d.type === 'error') {
                streamSession.hadError = true;
                showToast(d.content, true);
            } else if (d.type === 'cancelled') {
                streamSession.wasCancelled = true;
            }
        } catch (e) {}
    });
}

function finishStreamSession({ aborted = false } = {}) {
    if (el.procBar) el.procBar.classList.add('d-none');
    el.abort.classList.add('d-none');
    currentTaskId = null;
    uploadUiActive = false;

    if (aborted) {
        streamSession = null;
        return;
    }
    if (streamSession?.wasCancelled) {
        el.stat.innerText = "停止されました";
        // 停止時もローカル音声を残せるようにする
        showErrorDownloadButton(true);
        streamSession = null;
        return;
    }
    if (streamSession?.hadError) {
        el.stat.innerText = "エラー";
        el.copy.disabled = true;
        el.re.disabled = true;
        el.delBtn.disabled = true;
        el.fix.disabled = true;
        if (el.correctRephrase) el.correctRephrase.disabled = true;
        // リロードで消える前に保存できるようDLボタンを表示
        showErrorDownloadButton(true);
        streamSession = null;
        return;
    }

    el.stat.innerText = "完了";
    el.copy.disabled = el.re.disabled = el.delBtn.disabled = false;
    syncPostprocessButtons();
    hideErrorDownloadButton();
    showToast("完了");
    loadHistory();
    // モデル別のヒント表示
    const _mdl = getModel();
    const _lvl = el.think.value;
    if (_mdl === 'grok-stt') {
        el.tho.innerText = '[Grok STT は思考プロセスを提供しません。APIが直接文字起こし結果を返します。]';
    } else if (_mdl === 'grok-live-transcribe') {
        el.tho.innerText = '[Grok Live はリアルタイムのWebSocketで受信済みです(バッチアップロードにフォールバックしました)。]';
    } else if (_mdl.startsWith('gpt-') || _mdl === 'whisper-1') {
        el.tho.innerText = '[OpenAIモデルは思考プロセスを提供しません。APIが直接文字起こし結果を返します。]';
    } else if (_mdl === 'gemini-3.1-flash-lite' && (_lvl === 'LOW' || _lvl === 'MEDIUM') && !el.tho.innerText.trim()) {
        el.tho.innerText = '[Flash-Lite は LOW/MEDIUM 設定時に思考プロセスを返しません。表示するには HIGH を選択してください。]';
    }
    streamSession = null;
}

async function handleStreamResponse(r) {
    const reader = r.body.getReader(), dec = new TextDecoder();
    beginStreamSession(r.headers.get('X-Task-ID'));
    let isAborted = false;
    try {
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            consumeStreamChunk(dec.decode(value, { stream: true }));
        }
    } catch (e) {
        if (e.name === 'AbortError') {
            isAborted = true;
        } else {
            if (streamSession) streamSession.hadError = true;
            showToast(isNetworkLikeError(e) ? "通信エラー" : "Error", true);
        }
    }
    finishStreamSession({ aborted: isAborted });
}

// アップロードキャンセル後に、ストリームが開始される前に作られた可能性のある
// バックグラウンドタスクをサーバー側で停止するための安全網
async function cancelActiveServerTask() {
    try {
        const r = await csrfFetch('/api/tasks');
        const tasks = await r.json();
        for (const t of tasks) {
            if (t.status === 'running') {
                await csrfFetch(`/api/tasks/${encodeURIComponent(t.id)}/cancel`, { method: 'POST' });
            }
        }
    } catch (_) {}
}

async function uploadFile(append = false) {
    if (!selectedFile) return;
    if (isBatchMode(getModel())) {
        const result = await ensureApiKeyForModel(getModel(), false);
        if (!result.proceed) { el.stat.innerText = "待機中"; return; }
        el.stat.innerText = "Batchに投入中...";
        btnUploadNew.disabled = btnUploadAppend.disabled = true;
        const ok = await submitBatchJob({ action: 'transcribe', model: result.model }, selectedFile, selectedFile.name);
        btnUploadNew.disabled = btnUploadAppend.disabled = false;
        el.stat.innerText = ok ? "Batchに投入しました" : "エラー";
        return;
    }
    if (selectedFile.size > CHUNK_THRESHOLD) {
        await uploadFileChunked(selectedFile, append);
    } else {
        await uploadFileSingle(selectedFile, append);
    }
}

async function uploadFileSingle(file, append) {
    const model = getModel();
    const result = await ensureApiKeyForModel(model, false);
    if (!result.proceed) { el.stat.innerText = "待機中"; return; }
    const useModel = result.model;
    isAppendMode = append;
    rememberLocalAudio(file, file.name);
    hideErrorDownloadButton();
    // 録音の「新規」と同様、送信確定時点で結果テキストをクリアする
    if (!append) clearResultUiForNew();
    uploadUiActive = true;
    uploadCancelled = false;
    activeUploadId = null;
    showUploadProgress(0, "アップロード中");
    btnUploadNew.disabled = btnUploadAppend.disabled = true;
    abortController = new AbortController();
    let lastUploadPercent = 0;
    const fd = new FormData();
    fd.append('audio_file', file);
    fd.append('thinking_level', el.think.value);
    fd.append('model', useModel);
    fd.append('allow_rephrase_correction', el.rephrase && el.rephrase.checked ? '1' : '0');
    fd.append('allow_filler_removal', el.filler && el.filler.checked ? '1' : '0');
    fd.append('is_append', append ? '1' : '0');
    try {
        await sendFormDataWithProgress('/transcribe', fd, {
            signal: abortController.signal,
            onUploadProgress: (loaded, total) => {
                if (!total || !uploadUiActive) return;
                lastUploadPercent = (loaded / total) * 100;
                showUploadProgress(lastUploadPercent, lastUploadPercent >= 100 ? "アップロード完了・サーバーで処理中..." : "アップロード中");
            },
            onResponseStart: (xhr) => {
                beginStreamSession(xhr.getResponseHeader('X-Task-ID'));
                el.stat.innerText = "解析中...";
            },
            onResponseChunk: consumeStreamChunk
        });
        finishStreamSession();
    } catch (e) {
        if (e.name === 'AbortError') {
            hideUploadProgress();
            if (streamSession) {
                streamSession.wasCancelled = true;
                finishStreamSession();
            } else {
                el.stat.innerText = "停止されました";
                showErrorDownloadButton(true);
            }
            return;
        }
        // アップロード段階の失敗は進捗パネルをエラー表示のまま残す
        // 解析ストリーム開始後の失敗はステータス横のDLボタンで対応
        const inUploadPhase = !streamSession;
        if (inUploadPhase && !append) loadHistory();
        handleLocalAudioFailure(e, {
            keepProgressPanel: inUploadPhase,
            percent: inUploadPhase ? lastUploadPercent : null
        });
    } finally {
        uploadUiActive = false;
        activeUploadId = null;
        btnUploadNew.disabled = btnUploadAppend.disabled = false;
    }
}

async function uploadFileChunked(file, append) {
    const model = getModel();
    const result = await ensureApiKeyForModel(model, false);
    if (!result.proceed) { btnUploadNew.disabled = btnUploadAppend.disabled = false; el.stat.innerText = "待機中"; return; }
    const useModel = result.model;
    isAppendMode = append;
    rememberLocalAudio(file, file.name);
    hideErrorDownloadButton();
    btnUploadNew.disabled = btnUploadAppend.disabled = true;
    abortController = new AbortController();
    uploadUiActive = true;
    uploadCancelled = false;

    const totalChunks = Math.ceil(file.size / CHUNK_SIZE);
    const uploadId = Date.now() + '_' + Math.random().toString(36).substr(2, 9);
    activeUploadId = uploadId;
    const tasks = [];
    const chunkProgress = new Array(totalChunks).fill(0);
    let lastUploadPercent = 0;
    let chunkPhaseFailed = false;

    for (let i = 0; i < totalChunks; i++) {
        const start = i * CHUNK_SIZE;
        const end = Math.min(start + CHUNK_SIZE, file.size);
        const chunk = file.slice(start, end);
        const fd = new FormData();
        fd.append('upload_id', uploadId);
        fd.append('chunk_index', i);
        fd.append('total_chunks', totalChunks);
        fd.append('original_filename', file.name);
        fd.append('chunk', chunk, `chunk_${i}`);
        tasks.push({ index: i, size: chunk.size, formData: fd });
    }

    const CONCURRENCY = 4;
    const refreshUploadState = () => {
        if (!uploadUiActive || chunkPhaseFailed) return;
        const uploadedBytes = chunkProgress.reduce((sum, value) => sum + value, 0);
        lastUploadPercent = file.size > 0 ? (uploadedBytes / file.size) * 100 : 100;
        showUploadProgress(lastUploadPercent, lastUploadPercent >= 100 ? "アップロード完了" : "アップロード中");
    };

    async function uploadOne(task) {
        if (chunkPhaseFailed || abortController.signal.aborted) {
            throw new DOMException('Aborted', 'AbortError');
        }
        await sendFormDataWithProgress('/api/upload_chunk', task.formData, {
            signal: abortController.signal,
            onUploadProgress: (loaded, total) => {
                if (chunkPhaseFailed || !uploadUiActive) return;
                const estimated = total ? Math.min(task.size, (loaded / total) * task.size) : task.size;
                chunkProgress[task.index] = estimated;
                refreshUploadState();
            }
        });
        if (chunkPhaseFailed) return;
        chunkProgress[task.index] = task.size;
        refreshUploadState();
    }

    try {
        showUploadProgress(0, "アップロード中");
        for (let i = 0; i < tasks.length; i += CONCURRENCY) {
            if (chunkPhaseFailed || abortController.signal.aborted) break;
            const batch = tasks.slice(i, i + CONCURRENCY);
            try {
                await Promise.all(batch.map(uploadOne));
            } catch (batchErr) {
                chunkPhaseFailed = true;
                uploadUiActive = false;
                // 残りの並列送信を止めて、進捗の上書きを防ぐ
                if (!abortController.signal.aborted) {
                    try { abortController.abort(); } catch (_) {}
                }
                throw batchErr;
            }
            if (abortController.signal.aborted) {
                throw new DOMException('Aborted', 'AbortError');
            }
        }

        showUploadProgress(100, "送信完了");
        el.stat.innerText = "解析中...";
        // チャンク結合〜文字起こし開始直前にクリア（サーバー側クリアとタイミングを合わせる）
        if (!append) clearResultUiForNew();
        // 完了要求用に新しい AbortController（チャンク失敗時の abort とは分離）
        abortController = new AbortController();
        const fd = new FormData();
        fd.append('upload_id', uploadId);
        fd.append('total_chunks', totalChunks);
        fd.append('original_filename', file.name);
        fd.append('thinking_level', el.think.value);
        fd.append('model', useModel);
        fd.append('allow_rephrase_correction', el.rephrase && el.rephrase.checked ? '1' : '0');
        fd.append('allow_filler_removal', el.filler && el.filler.checked ? '1' : '0');
        fd.append('is_append', append ? '1' : '0');

        await sendFormDataWithProgress('/api/upload_complete', fd, {
            signal: abortController.signal,
            onResponseStart: (xhr) => {
                beginStreamSession(xhr.getResponseHeader('X-Task-ID'));
                el.stat.innerText = "解析中...";
            },
            onResponseChunk: consumeStreamChunk
        });
        finishStreamSession();
    } catch (e) {
        // 並列停止用に abort した場合でも、元の失敗理由 (batchErr) がここに届く
        if (e.name === 'AbortError') {
            hideUploadProgress();
            if (streamSession) {
                streamSession.wasCancelled = true;
                finishStreamSession();
            } else {
                el.stat.innerText = "停止されました";
                showErrorDownloadButton(true);
            }
            return;
        }
        const inUploadPhase = !streamSession;
        if (inUploadPhase && !append) loadHistory();
        handleLocalAudioFailure(e, {
            keepProgressPanel: inUploadPhase,
            percent: inUploadPhase ? lastUploadPercent : null
        });
    } finally {
        uploadUiActive = false;
        activeUploadId = null;
        btnUploadNew.disabled = btnUploadAppend.disabled = false;
    }
}

function initStream() {
    btnUploadNew.onclick = () => uploadFile(false);
    btnUploadAppend.onclick = () => uploadFile(true);
    if (btnUploadCancel) {
        btnUploadCancel.onclick = async () => {
            if (uploadCancelled) return;
            uploadCancelled = true;
            if (uploadCancelRow) uploadCancelRow.classList.add('d-none');
            if (abortController) {
                try { abortController.abort(); } catch (_) {}
            }
            // チャンクアップロード中はサーバー側のチャンクも掃除する
            if (activeUploadId) {
                const fd = new FormData();
                fd.append('upload_id', activeUploadId);
                try { await csrfFetch('/api/upload_cancel', { method: 'POST', body: fd }); } catch (_) {}
            }
            await cancelActiveServerTask();
        };
    }
    CHUNK_THRESHOLD = 100 * 1024 * 1024;
    CHUNK_SIZE = 5 * 1024 * 1024;
}
