// Batch 処理（Gemini Batch API）: 「Batchで実行」トグル・投入・完了検知ポーリング・取り込み確認・一覧画面（/batch）。
// index.html と batch.html の両方で読み込む。`el` などメイン画面専用のグローバルは typeof で存在確認してから使う。

const BATCH_MODELS = ['gemini-3.8-flash', 'gemini-3.7-flash', 'gemini-3.6-flash', 'gemini-3.5-flash', 'gemini-3.5-flash-lite', 'gemini-3-flash-preview', 'gemini-3.1-flash-lite'];
const BATCH_POLL_MS = 60000;
let batchPollTimer = null;
let batchDialogQueue = [];
let batchDialogJob = null;

function isBatchModel(model) { return BATCH_MODELS.includes(model); }

/** トグルが有効かつモデルが Batch 対応のときだけ true */
function isBatchMode(model) {
    const t = document.getElementById('batchMode');
    return !!(t && t.checked && isBatchModel(model));
}

/** モデルに応じて「Batchで実行」トグルの表示を切り替える（settings.js の setModel から呼ぶ） */
function syncBatchToggle() {
    const wrap = document.getElementById('batchModeWrap');
    if (!wrap) return;
    const model = typeof getModel === 'function' ? getModel() : '';
    wrap.classList.toggle('d-none', !isBatchModel(model));
}

function batchStatusLabel(s) {
    return { running: '処理中', succeeded: '完了', failed: '失敗', cancelled: '取消', expired: '期限切れ' }[s] || s;
}
function batchActionLabel(a) {
    return { transcribe: '文字起こし', reanalyze: '再分析', improve: '改善' }[a] || a;
}

/** Batch へ投入する。fields: {action, model, text?, instruction?, use_audio?}、file: 音声(任意) */
async function submitBatchJob(fields, file, fileName) {
    const fd = new FormData();
    Object.entries(fields).forEach(([k, v]) => fd.append(k, v));
    fd.append('thinking_level', (typeof el !== 'undefined' && el.think) ? el.think.value : 'LOW');
    fd.append('allow_rephrase_correction', (typeof el !== 'undefined' && el.rephrase && el.rephrase.checked) ? '1' : '0');
    fd.append('allow_filler_removal', (typeof el !== 'undefined' && el.filler && el.filler.checked) ? '1' : '0');
    if (file) fd.append('audio_file', file, fileName || file.name);
    try {
        const r = await csrfFetch('/api/batches', { method: 'POST', body: fd });
        const data = await r.json().catch(() => ({}));
        if (!r.ok) { showToast(data.error || 'Batchの投入に失敗しました', true); return false; }
        showToast('Batchに投入しました（完了後に取り込めます）');
        startBatchPolling();
        return true;
    } catch (e) {
        showToast('通信エラー', true);
        return false;
    }
}

function notifiedBatchIds() {
    try { return JSON.parse(localStorage.getItem('batch_notified') || '[]'); } catch (e) { return []; }
}
function markBatchNotified(id) {
    try {
        const ids = notifiedBatchIds();
        if (!ids.includes(id)) ids.push(id);
        localStorage.setItem('batch_notified', JSON.stringify(ids.slice(-200)));
    } catch (e) {}
}

async function fetchBatchJobs() {
    const r = await csrfFetch('/api/batches');
    if (!r.ok) throw new Error('batch list failed');
    return r.json();
}

/** 一覧を取得して完了ジョブの取り込み確認を出し、進行中があれば次回ポーリングを予約する */
async function batchPollTick() {
    batchPollTimer = null;
    let jobs;
    try { jobs = await fetchBatchJobs(); } catch (e) { scheduleBatchPoll(); return; }
    const done = notifiedBatchIds();
    jobs.filter(j => j.status === 'succeeded' && !j.imported && !done.includes(j.id) && !batchDialogQueue.some(q => q.id === j.id) && !(batchDialogJob && batchDialogJob.id === j.id))
        .forEach(j => batchDialogQueue.push(j));
    showNextBatchDialog();
    if (typeof renderBatchList === 'function') renderBatchList(jobs);
    if (jobs.some(j => j.status === 'running')) scheduleBatchPoll();
}
function scheduleBatchPoll() {
    if (!batchPollTimer) batchPollTimer = setTimeout(() => {
        if (document.hidden) { batchPollTimer = null; scheduleBatchPoll(); } else { batchPollTick(); }
    }, BATCH_POLL_MS);
}
function startBatchPolling(immediate = false) {
    if (batchPollTimer) { clearTimeout(batchPollTimer); batchPollTimer = null; }
    if (immediate) batchPollTick(); else scheduleBatchPoll();
}

function showNextBatchDialog() {
    const modalEl = document.getElementById('batchDoneModal');
    if (!modalEl || batchDialogJob || !batchDialogQueue.length) return;
    batchDialogJob = batchDialogQueue.shift();
    document.getElementById('batchDoneInfo').textContent = `${batchActionLabel(batchDialogJob.action)}（${batchDialogJob.model}）／ ${batchDialogJob.created}`;
    bootstrap.Modal.getOrCreateInstance(modalEl).show();
}

/** 結果を履歴へ取り込み、メイン画面なら結果欄にも反映する */
async function importBatchJob(id) {
    try {
        const r = await csrfFetch(`/api/batches/${id}/import`, { method: 'POST' });
        const data = await r.json().catch(() => ({}));
        if (!r.ok) { showToast(data.error || '取り込みに失敗しました', true); return false; }
        markBatchNotified(id);
        if (typeof el !== 'undefined' && el && el.res) {
            el.res.value = data.result || '';
            el.copy.disabled = el.re.disabled = el.delBtn.disabled = false;
            if (typeof syncPostprocessButtons === 'function') syncPostprocessButtons();
            if (typeof loadHistory === 'function') loadHistory();
            showToast('Batchの結果を取り込みました');
        } else {
            showToast('履歴に取り込みました。ワークスペースで確認できます');
        }
        return true;
    } catch (e) {
        showToast('通信エラー', true);
        return false;
    }
}

async function cancelBatchJob(id) {
    await csrfFetch(`/api/batches/${id}/cancel`, { method: 'POST' });
    refreshBatchList();
}
async function deleteBatchJob(id) {
    await csrfFetch(`/api/batches/${id}`, { method: 'DELETE' });
    refreshBatchList();
}
async function refreshBatchList() {
    try { renderBatchList(await fetchBatchJobs()); } catch (e) { showToast('一覧の取得に失敗しました', true); }
}

/** batch.html の一覧描画（コンテナ #batchListContainer がある画面のみ） */
function renderBatchList(jobs) {
    const box = document.getElementById('batchListContainer');
    if (!box) return;
    if (!jobs.length) { box.innerHTML = '<div class="text-center text-muted small py-4">Batchジョブはありません</div>'; return; }
    const badge = { running: 'bg-info', succeeded: 'bg-success', failed: 'bg-danger', cancelled: 'bg-secondary', expired: 'bg-secondary' };
    box.innerHTML = jobs.map(j => `
        <div class="list-group-item d-flex flex-wrap justify-content-between align-items-center gap-2" data-id="${j.id}">
            <div class="me-auto">
                <span class="badge ${badge[j.status] || 'bg-secondary'} me-1">${escapeHtml(batchStatusLabel(j.status))}</span>
                ${j.status === 'succeeded' ? (j.imported ? '<span class="badge bg-light text-dark border me-1">取り込み済み</span>' : '<span class="badge bg-warning text-dark me-1">未取り込み</span>') : ''}
                <strong>${escapeHtml(batchActionLabel(j.action))}</strong>
                <small class="text-muted ms-1">${escapeHtml(j.model)}</small>
                <div class="small text-muted">${escapeHtml(j.created)}${j.completed ? ' → ' + escapeHtml(j.completed) : ''}${j.input ? ' ／ ' + escapeHtml(j.input) : ''}</div>
                ${j.error ? `<div class="small text-danger">${escapeHtml(j.error)}</div>` : ''}
            </div>
            <div class="d-flex gap-1">
                ${j.status === 'succeeded' ? `<button class="btn btn-primary btn-sm" data-act="import">${j.imported ? '再取り込み' : '取り込む'}</button>` : ''}
                ${j.status === 'running' ? '<button class="btn btn-outline-warning btn-sm" data-act="cancel">取消</button>' : ''}
                ${j.status !== 'running' ? '<button class="btn btn-outline-danger btn-sm" data-act="delete"><i class="bi bi-trash"></i></button>' : ''}
            </div>
        </div>`).join('');
}

function initBatch() {
    syncBatchToggle();
    const toggle = document.getElementById('batchMode');
    if (toggle) {
        try { toggle.checked = localStorage.getItem('stt_batch') === 'true'; } catch (e) {}
        toggle.addEventListener('change', () => { try { localStorage.setItem('stt_batch', toggle.checked); } catch (e) {} });
    }
    const modalEl = document.getElementById('batchDoneModal');
    if (modalEl) {
        document.getElementById('batchDoneImport').onclick = async () => {
            const job = batchDialogJob;
            bootstrap.Modal.getOrCreateInstance(modalEl).hide();
            if (job) await importBatchJob(job.id);
        };
        modalEl.addEventListener('hidden.bs.modal', () => {
            if (batchDialogJob) markBatchNotified(batchDialogJob.id);
            batchDialogJob = null;
            setTimeout(showNextBatchDialog, 300);
        });
    }
    const box = document.getElementById('batchListContainer');
    if (box) {
        box.addEventListener('click', async (e) => {
            const btn = e.target.closest('button[data-act]');
            if (!btn) return;
            const id = Number(btn.closest('[data-id]').dataset.id);
            if (btn.dataset.act === 'import') { await importBatchJob(id); refreshBatchList(); }
            else if (btn.dataset.act === 'cancel') cancelBatchJob(id);
            else if (btn.dataset.act === 'delete') deleteBatchJob(id);
        });
        const refresh = document.getElementById('batchRefresh');
        if (refresh) refresh.onclick = refreshBatchList;
    }
    startBatchPolling(true);
}

// batch.html（main.js を読み込まない）では自前で初期化する。index では main.js が initBatch() を呼ぶ。
if (document.getElementById('batchListContainer') || document.currentScript?.dataset.autoInit) {
    document.addEventListener('DOMContentLoaded', initBatch);
}
