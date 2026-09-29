// 履歴の読み込み・実行中タスクへの再接続・ファイルマネージャ。

// --- History Functions ---
async function loadHistory() {
    try {
        const r = await csrfFetch('/api/history');
        const data = await r.json();
        if(data.length === 0) { el.hist.innerHTML = '<div class="text-center text-muted small py-3">履歴なし</div>'; return; }
        
        el.hist.innerHTML = data.map(h => `
                <div class="history-item ${h.expired ? 'expired' : ''}" data-full-result="${btoa(unescape(encodeURIComponent(h.result || '')))}">
                    <div class="history-meta">
                        <span>${escapeHtml(h.action.toUpperCase())}</span>
                        <div>
                            <span class="me-2">${escapeHtml(h.time)}</span>
                            <i class="bi bi-clipboard text-primary cursor-pointer me-1" onclick="copyHistoryText(this)" title="コピー"></i>
                            <i class="bi bi-trash text-danger cursor-pointer" onclick="deleteHistoryItem(${h.id})" title="削除"></i>
                        </div>
                    </div>
                    ${h.input ? `<div class="small fw-bold text-truncate">${escapeHtml(h.input)}</div>` : ''}
                    ${h.thought ? `<div class="history-thought" onclick="this.classList.toggle('expanded')" title="クリックで展開">${escapeHtml(h.thought.substring(0,50))}${h.thought.length > 50 ? '...' : ''}</div>` : ''}
                    <div class="small mt-1" style="white-space:pre-wrap;">${h.result ? `${escapeHtml(h.result.substring(0, 100))}${h.result.length>100?'...':''}` : '(結果なし)'}</div>
                </div>
            `).join('');
    } catch(e){}
}

async function checkRunningTasks() {
    try {
        const r = await csrfFetch('/api/tasks');
        const tasks = await r.json();
        const runningTask = tasks.find(t => t.status === 'running');
        if (runningTask) {
            currentTaskId = runningTask.id;
            abortController = new AbortController();
            el.stat.innerText = "バックグラウンド処理を再開中...";
            showToast("前回の処理が続行中です");
            try {
                const streamR = await fetch(`/api/task_stream/${runningTask.id}`, {
                    signal: abortController.signal
                });
                if (streamR.ok) {
                    await handleStreamResponse(streamR);
                }
            } catch(e) {
                if (e.name !== 'AbortError') {
                    showToast("再接続失敗", true);
                }
            }
        }
        // completed tasks from Redis are already in History DB
    } catch(e) {}
}

async function parallelDownload(url, filename, fileSize) {
    const DL_CHUNK = 10 * 1024 * 1024; // 10MB per download chunk
    const totalChunks = Math.ceil(fileSize / DL_CHUNK);
    const CONCURRENCY = 4;
    showToast(`並列ダウンロード開始 (${totalChunks}分割)...`);

    async function dlChunk(start, end) {
        const r = await fetch(url, { headers: { 'Range': `bytes=${start}-${end}` } });
        if (!r.ok) throw new Error('Download failed');
        return r.blob();
    }

    try {
        const chunks = [];
        for (let i = 0; i < totalChunks; i++) {
            const start = i * DL_CHUNK;
            const end = Math.min(start + DL_CHUNK - 1, fileSize - 1);
            chunks.push({ start, end });
        }

        const blobChunks = [];
        for (let i = 0; i < chunks.length; i += CONCURRENCY) {
            const batch = chunks.slice(i, i + CONCURRENCY);
            const results = await Promise.all(batch.map(c => dlChunk(c.start, c.end)));
            blobChunks.push(...results);
        }

        const blob = new Blob(blobChunks);
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = filename;
        a.click();
        URL.revokeObjectURL(a.href);
        showToast("ダウンロード完了");
    } catch(e) {
        showToast("ダウンロード失敗", true);
    }
}

function initHistory() {
    window.deleteHistoryItem = async (id) => {
        if(!confirm('この履歴を削除しますか？')) return;
        try {
            const r = await csrfFetch(`/api/delete_history/${id}`, {method:'POST'});
            if(r.ok) { showToast("履歴を削除しました"); loadHistory(); }
        } catch(e){ showToast("削除失敗", true); }
    };
    window.copyHistoryText = (el) => {
        const raw = el.closest('.history-item').dataset.fullResult;
        if(!raw) return;
        const text = decodeURIComponent(escape(atob(raw)));
        navigator.clipboard.writeText(text).then(() => showToast("コピーしました")).catch(() => showToast("コピー失敗", true));
    };
    el.reset.onclick = async () => {
        if(!confirm('すべての履歴と保存データを削除して、完全に新しいセッションを開始しますか？')) return;
        try {
            const r = await csrfFetch('/api/clear_all', {method:'POST'});
            if(r.ok) {
                showToast("セッションと保存データをリセットしました");
                el.res.value = "";
                el.tho.innerText = "";
                el.re.disabled = el.delBtn.disabled = true;
                if (el.fix) el.fix.disabled = true;
                if (el.correctRephrase) el.correctRephrase.disabled = true;
                loadHistory();
            } else {
                showToast("リセットに失敗しました", true);
            }
        } catch(e) { showToast("通信エラー", true); }
    };
    // --- File Manager ---
    window.loadFileMetadata = async function() {
        const c = document.getElementById('fileListContainer'); c.innerHTML = 'Loading...';
        try {
            const r = await csrfFetch('/api/files'); const files = await r.json();
            c.innerHTML = files.length ? files.map(f => {
                const sizeMB = (f.size / (1024*1024)).toFixed(2);
                const isLarge = f.size > CHUNK_THRESHOLD;
                const playerHtml = isLarge
                    ? `<div class="d-flex gap-2"><span class="small text-muted">${sizeMB} MB</span><button class="btn btn-sm btn-outline-primary download-file-btn py-0" data-url="${escapeHtml(f.url)}" data-filename="${escapeHtml(f.filename)}" data-size="${f.size}"><i class="bi bi-download me-1"></i>並列DL</button></div>`
                    : `<audio controls src="${escapeHtml(f.url)}" style="height:25px;width:100%"></audio><span class="small text-muted ms-1">${sizeMB} MB</span>`;
                return `<div class="list-group-item d-flex justify-content-between align-items-center p-2">
                    <div class="flex-grow-1 me-2"><div class="small fw-bold">${escapeHtml(f.display_name)}</div>${playerHtml}</div>
                    <button class="btn btn-outline-danger btn-sm delete-file-btn" data-filename="${escapeHtml(f.filename)}"><i class="bi bi-trash"></i></button>
                </div>`;
            }).join('') : '<div class="text-center py-2 text-muted">保存された音声はありません</div>';
        } catch(e){ c.innerHTML='エラー'; }
    };
    window.deleteFileItem = async (filename) => {
        if(!confirm('このファイルを削除しますか？')) return;
        try {
            const r = await csrfFetch(`/api/delete_file/${filename}`, {method:'POST'});
            if(r.ok) { showToast("ファイルを削除しました"); loadFileMetadata(); }
        } catch(e){ showToast("削除失敗", true); }
    };
    document.getElementById('fileListContainer').addEventListener('click', (e) => {
        const btn = e.target.closest('.delete-file-btn');
        if (btn) {
            deleteFileItem(btn.dataset.filename);
        }
        const dlBtn = e.target.closest('.download-file-btn');
        if (dlBtn) {
            parallelDownload(dlBtn.dataset.url, dlBtn.dataset.filename, parseInt(dlBtn.dataset.size));
        }
    });
}
