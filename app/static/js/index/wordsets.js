// 単語セット（有効化モーダル・管理モーダル・読み仮名自動生成）。
// --- Word Set Manager (Global Scope) ---
window.loadWordSetStatus = async function() {
    const container = document.getElementById('wordSetListContainer');
    if (!container) return;
    container.innerHTML = '<div class="text-center py-2">読み込み中...</div>';
    try {
        const r = await csrfFetch('/api/word_sets');
        const sets = await r.json();
        updateWordSetButtonText(sets);
        if (sets.length === 0) {
            container.innerHTML = '<div class="text-center py-3 text-muted">セットがありません。<br><button type="button" class="btn btn-sm btn-link p-0 align-baseline" onclick="openWordSetManageModal()">新しく作成</button></div>';
            return;
        }
        container.innerHTML = sets.map(s => `
            <div class="list-group-item d-flex justify-content-between align-items-center p-2">
                <span class="fw-bold">${escapeHtml(s.name)}</span>
                <div class="d-flex align-items-center gap-2">
                    <button type="button" class="btn btn-sm btn-outline-secondary p-1 lh-1" onclick="openWordSetEditModal(${s.id})" title="編集">
                        <i class="bi bi-pencil"></i>
                    </button>
                    <div class="form-check form-switch m-0">
                        <input class="form-check-input" type="checkbox" role="switch" id="ws_${s.id}" ${s.is_active ? 'checked' : ''} onchange="toggleWordSet(${s.id})">
                    </div>
                </div>
            </div>
        `).join('');
    } catch(e) { container.innerHTML = 'エラー'; }
};

window.toggleWordSet = async function(id) {
    try {
        const r = await csrfFetch(`/api/word_sets/toggle/${id}`, {method:'POST'});
        if(r.ok) { 
            showToast("単語セットの有効/無効を切り替えました");
            const r2 = await csrfFetch('/api/word_sets');
            const sets = await r2.json();
            updateWordSetButtonText(sets);
        } else {
            showToast("切り替え失敗", true);
        }
    } catch(e){ showToast("通信エラー", true); }
};

window.resetWordSets = async function() {
    try {
        const r = await csrfFetch('/api/word_sets/reset', {method:'POST'});
        if(r.ok) {
            showToast("すべてのセットを無効化しました");
            loadWordSetStatus(); // リストとボタン両方を更新
        }
    } catch(e) { showToast("通信エラー", true); }
};

// --- Word Set Management Modal Logic ---
let currentManageSetId = "";
window.openWordSetEditModal = async function(setId) {
    const manageModal = new bootstrap.Modal(document.getElementById('wordSetManageModal'));
    const activationModal = bootstrap.Modal.getInstance(document.getElementById('wordSetModal'));
    if (activationModal) activationModal.hide();
    manageModal.show();
    await refreshManageList(setId.toString());
};

window.openWordSetManageModal = async function() {
    const manageModal = new bootstrap.Modal(document.getElementById('wordSetManageModal'));
    const activationModal = bootstrap.Modal.getInstance(document.getElementById('wordSetModal'));
    if (activationModal) activationModal.hide();
    manageModal.show();
    await refreshManageList(currentManageSetId);
};

async function refreshManageList(selectedId = "") {
    const container = document.getElementById('wordSetsManageContainer');
    const selector = document.getElementById('manageSetSelector');
    
    // 1. セット一覧 (JSON) を取得してセレクターを更新
    const resSets = await csrfFetch('/api/word_sets');
    const sets = await resSets.json();
    selector.innerHTML = '<option value="">-- 管理するセットを選択してください --</option>' + 
        sets.map(s => `<option value="${s.id}" ${s.id == selectedId ? 'selected' : ''}>${escapeHtml(s.name)}</option>`).join('');
    
    // 2. 管理用HTML (Partial) を取得してコンテナを更新
    const resHtml = await csrfFetch('/api/word_sets/manage_html');
    const html = await resHtml.text(); 
    container.innerHTML = html;

    currentManageSetId = selectedId.toString();
    updateManageVisibility();
}

function updateManageVisibility() {
    const container = document.getElementById('wordSetsManageContainer');
    const cards = container.querySelectorAll('.word-set-card');
    cards.forEach(card => {
        card.style.display = (card.dataset.id === currentManageSetId) ? 'block' : 'none';
    });
}

// モーダル内のイベントリスナー設定
document.addEventListener('DOMContentLoaded', () => {
    document.getElementById('btnWordSet').addEventListener('click', function() {
        loadWordSetStatus();
        bootstrap.Modal.getOrCreateInstance(document.getElementById('wordSetModal')).show();
    });

    const selector = document.getElementById('manageSetSelector');
    const container = document.getElementById('wordSetsManageContainer');

    selector.addEventListener('change', () => {
        currentManageSetId = selector.value;
        updateManageVisibility();
    });

    document.getElementById('createSetForm').addEventListener('submit', async (e) => {
        e.preventDefault();
        const formData = new FormData(e.target);
        const res = await csrfFetch('/api/word_sets/create', { method: 'POST', body: formData });
        if (res.ok) {
            const data = await res.json();
            e.target.reset();
            await refreshManageList(data.id);
        }
    });

    container.addEventListener('click', async (e) => {
        const target = e.target;
        if (target.closest('.delete-set-btn')) {
            if (!confirm('このセットを削除しますか？')) return;
            const id = target.closest('.delete-set-btn').dataset.id;
            await csrfFetch(`/api/word_sets/delete/${id}`, { method: 'POST' });
            await refreshManageList("");
        }
        if (target.closest('.delete-word-btn')) {
            const id = target.closest('.delete-word-btn').dataset.id;
            await csrfFetch(`/api/words/delete/${id}`, { method: 'POST' });
            await refreshManageList(currentManageSetId);
        }
    });

    container.addEventListener('submit', async (e) => {
        if (e.target.classList.contains('add-word-form')) {
            e.preventDefault();
            const formData = new FormData(e.target);
            await csrfFetch('/api/words/add', { method: 'POST', body: formData });
            await refreshManageList(currentManageSetId);
        }
    });

    // Auto-generate yomigana from replacement word
    let yomiganaTimer = null;
    container.addEventListener('input', async (e) => {
        const form = e.target.closest('.add-word-form');
        if (!form) return;
        const cb = form.querySelector('.auto-yomigana-cb');
        if (!cb || !cb.checked) return;
        const readingInput = form.querySelector('input[name="reading"]');
        const replacementInput = form.querySelector('input[name="replacement"]');
        if (!readingInput || !replacementInput || e.target !== replacementInput) return;
        const word = replacementInput.value.trim();
        if (!word) return;
        clearTimeout(yomiganaTimer);
        yomiganaTimer = setTimeout(async () => {
            const model = localStorage.getItem('stt_yomigana_m') || 'gemini-3.5-flash';
            try {
                const fd = new FormData();
                fd.append('word', word);
                fd.append('model', model);
                const r = await csrfFetch('/api/yomigana/generate', { method: 'POST', body: fd });
                const data = await r.json();
                if (data.reading) {
                    readingInput.value = data.reading;
                } else {
                    showToast('読みの自動生成に失敗しました: ' + (data.error || ('HTTP ' + r.status)), true);
                }
            } catch(e) {
                console.error('Yomigana generation failed', e);
                showToast('読みの自動生成に失敗しました', true);
            }
        }, 600);
    });
});

function updateWordSetButtonText(sets) {
    const btn = document.getElementById('btnWordSet');
    const resetBtn = document.getElementById('btnWordSetReset');
    if (!btn) return;
    const activeNames = sets.filter(s => s.is_active).map(s => s.name);
    if (activeNames.length > 0) {
        btn.innerHTML = `<i class="bi bi-journal-check"></i> ${escapeHtml(activeNames.join(', '))}`;
        btn.classList.remove('btn-outline-primary');
        btn.classList.add('btn-primary', 'text-white');
        if (resetBtn) resetBtn.style.display = '';
    } else {
        btn.innerHTML = `<i class="bi bi-journal-text"></i> 単語リスト`;
        btn.classList.remove('btn-primary', 'text-white');
        btn.classList.add('btn-outline-primary');
        if (resetBtn) resetBtn.style.display = 'none';
    }
}
