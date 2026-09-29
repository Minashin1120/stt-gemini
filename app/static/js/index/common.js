// 共通ユーティリティ（トースト・HTMLエスケープ・CSRF/JSチャレンジ付き fetch）。

// --- Global Utilities ---
function showToast(m, e=false) {
    const msgEl = document.getElementById('toastMessage');
    const toastEl = document.getElementById('liveToast');
    if (msgEl && toastEl) {
        msgEl.innerText = m;
        toastEl.classList.toggle('bg-danger', e);
        bootstrap.Toast.getOrCreateInstance(toastEl).show();
    }
}

const csrfToken = document.querySelector('meta[name="csrf-token"]')?.content || '';
function escapeHtml(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}
function csrfFetch(input, init = {}) {
    const headers = new Headers(init.headers || {});
    if (csrfToken && !headers.has('X-CSRFToken')) {
        headers.set('X-CSRFToken', csrfToken);
    }
    
    // JS Challenge injection for fetch
    const method = (init.method || 'GET').toUpperCase();
    if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) {
        if (init.body instanceof FormData) {
            if (!init.body.has('_js_challenge')) {
                init.body.append('_js_challenge', 'valid_' + csrfToken);
            }
        } else if (typeof init.body === 'string') {
            try {
                const obj = JSON.parse(init.body);
                obj._js_challenge = 'valid_' + csrfToken;
                init.body = JSON.stringify(obj);
            } catch (e) {
                // Not JSON, skip or handle as URLSearchParams if needed
            }
        }
    }
    
    return fetch(input, { ...init, headers });
}
