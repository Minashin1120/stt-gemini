// ローカル音声の保持、アップロード進捗UI、進捗付き FormData 送信。

function rememberLocalAudio(blobOrFile, name) {
    if (!blobOrFile) return;
    lastLocalAudioBlob = blobOrFile;
    lastLocalAudioName = name || blobOrFile.name || 'audio.mp3';
}

function downloadLocalAudio() {
    if (!lastLocalAudioBlob) {
        showToast('ダウンロードできる音声がありません', true);
        return;
    }
    const a = document.createElement('a');
    const url = URL.createObjectURL(lastLocalAudioBlob);
    a.href = url;
    a.download = lastLocalAudioName || 'audio.mp3';
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    showToast('音声のダウンロードを開始しました');
}

function showErrorDownloadButton(show = true) {
    const visible = !!(show && lastLocalAudioBlob);
    if (btnErrorDownload) btnErrorDownload.classList.toggle('d-none', !visible);
    if (uploadErrorActions) uploadErrorActions.classList.toggle('d-none', !visible);
}

function hideErrorDownloadButton() {
    if (btnErrorDownload) btnErrorDownload.classList.add('d-none');
    if (uploadErrorActions) uploadErrorActions.classList.add('d-none');
}

function showUploadProgress(percent = 0, label = 'アップロード中') {
    if (!uploadUiActive) return;
    const safePercent = Number.isFinite(percent) ? Math.max(0, Math.min(100, Math.round(percent))) : 0;
    if (uploadProgressPanel) {
        uploadProgressPanel.classList.remove('d-none', 'is-error');
    }
    if (uploadCancelRow) uploadCancelRow.classList.toggle('d-none', uploadCancelled);
    if (uploadErrorActions) uploadErrorActions.classList.add('d-none');
    if (uploadProgressLabel) uploadProgressLabel.innerText = label;
    if (uploadProgressPercent) {
        uploadProgressPercent.classList.remove('text-danger');
        uploadProgressPercent.classList.add('text-primary');
        uploadProgressPercent.innerText = `${safePercent}%`;
    }
    if (uploadProgressBar) {
        uploadProgressBar.style.width = `${safePercent}%`;
        uploadProgressBar.setAttribute('aria-valuenow', String(safePercent));
    }
    if (el.stat) el.stat.innerText = `${label} ${safePercent}%`;
}

function showUploadError(message = '通信エラー', percent = null) {
    uploadUiActive = false;
    if (uploadProgressPanel) {
        uploadProgressPanel.classList.remove('d-none');
        uploadProgressPanel.classList.add('is-error');
    }
    if (uploadCancelRow) uploadCancelRow.classList.add('d-none');
    if (uploadProgressLabel) uploadProgressLabel.innerText = message;
    if (uploadProgressPercent) {
        uploadProgressPercent.classList.remove('text-primary');
        uploadProgressPercent.classList.add('text-danger');
        if (percent != null && Number.isFinite(percent)) {
            uploadProgressPercent.innerText = `${Math.max(0, Math.min(100, Math.round(percent)))}%`;
        } else {
            uploadProgressPercent.innerText = '失敗';
        }
    }
    if (uploadProgressBar) {
        // 失敗時は「完了に見える100%」にしない。未指定時は止まった位置を維持。
        const current = parseInt(uploadProgressBar.getAttribute('aria-valuenow') || '0', 10);
        const shown = (percent != null && Number.isFinite(percent))
            ? Math.max(0, Math.min(100, Math.round(percent)))
            : (Number.isFinite(current) ? current : 0);
        uploadProgressBar.style.width = `${shown}%`;
        uploadProgressBar.setAttribute('aria-valuenow', String(shown));
    }
    if (el.stat) el.stat.innerText = message;
    showErrorDownloadButton(true);
}

function hideUploadProgress() {
    uploadUiActive = false;
    if (uploadProgressPanel) {
        uploadProgressPanel.classList.add('d-none');
        uploadProgressPanel.classList.remove('is-error');
    }
    if (uploadCancelRow) uploadCancelRow.classList.add('d-none');
    if (uploadErrorActions) uploadErrorActions.classList.add('d-none');
    if (uploadProgressLabel) uploadProgressLabel.innerText = 'アップロード準備中';
    if (uploadProgressPercent) {
        uploadProgressPercent.classList.remove('text-danger');
        uploadProgressPercent.classList.add('text-primary');
        uploadProgressPercent.innerText = '0%';
    }
    if (uploadProgressBar) {
        uploadProgressBar.style.width = '0%';
        uploadProgressBar.setAttribute('aria-valuenow', '0');
    }
}

function prepareFormData(formData) {
    if (csrfToken && !formData.has('csrf_token')) {
        formData.append('csrf_token', csrfToken);
    }
    if (!formData.has('_js_challenge')) {
        formData.append('_js_challenge', 'valid_' + csrfToken);
    }
    return formData;
}

function isNetworkLikeError(err) {
    if (!err) return false;
    if (err.name === 'AbortError') return false;
    const msg = String(err.message || '');
    if (msg === 'Network error' || msg === 'Failed to fetch' || msg === 'Load failed') return true;
    if (msg.startsWith('HTTP 0')) return true;
    if (typeof err.status === 'number' && err.status === 0) return true;
    return false;
}

function extractErrorMessage(err, fallback = '通信エラー') {
    if (!err) return fallback;
    if (isNetworkLikeError(err)) return '通信エラー';
    if (err.responseText) {
        try {
            const data = JSON.parse(err.responseText);
            if (data && data.error) return String(data.error);
        } catch (_) {}
    }
    if (typeof err.status === 'number' && err.status > 0) {
        return `エラー (HTTP ${err.status})`;
    }
    return err.message || fallback;
}

function handleLocalAudioFailure(err, { keepProgressPanel = false, percent = null } = {}) {
    const message = extractErrorMessage(err, '通信エラー');
    if (keepProgressPanel) {
        showUploadError(message, percent);
    } else {
        hideUploadProgress();
        if (el.stat) el.stat.innerText = message;
        showErrorDownloadButton(true);
    }
    if (streamSession) {
        streamSession.hadError = true;
        finishStreamSession();
    }
    showToast(message, true);
}

function sendFormDataWithProgress(url, formData, { signal, onUploadProgress, onResponseStart, onResponseChunk } = {}) {
    return new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        let lastResponseLength = 0;
        let responseStarted = false;
        let abortedBySignal = false;
        let settled = false;
        let uploadTransferOk = false;

        const settle = (fn, value) => {
            if (settled) return;
            settled = true;
            cleanup();
            fn(value);
        };

        const cleanup = () => {
            if (signal && abortHandler) {
                signal.removeEventListener('abort', abortHandler);
            }
        };

        const abortHandler = signal ? () => {
            abortedBySignal = true;
            try { xhr.abort(); } catch (_) {}
        } : null;

        xhr.open('POST', url, true);
        xhr.setRequestHeader('X-CSRFToken', csrfToken);

        xhr.upload.onprogress = (e) => {
            if (settled || !e.lengthComputable || typeof onUploadProgress !== 'function') return;
            onUploadProgress(e.loaded, e.total);
        };
        // 転送成功時のみ100%にする（失敗時に100%へ飛ばさない）
        xhr.upload.onload = () => {
            uploadTransferOk = true;
        };
        xhr.upload.onloadend = () => {
            if (settled || !uploadTransferOk || typeof onUploadProgress !== 'function') return;
            onUploadProgress(1, 1);
        };

        xhr.onreadystatechange = () => {
            if (!responseStarted && xhr.readyState >= 2 && xhr.status >= 200 && xhr.status < 300) {
                responseStarted = true;
                if (typeof onResponseStart === 'function') onResponseStart(xhr);
            }
            if (responseStarted && xhr.readyState >= 3 && typeof onResponseChunk === 'function') {
                const text = xhr.responseText || '';
                if (text.length > lastResponseLength) {
                    onResponseChunk(text.slice(lastResponseLength));
                    lastResponseLength = text.length;
                }
            }
            if (xhr.readyState === 4) {
                if (responseStarted && typeof onResponseChunk === 'function') {
                    const text = xhr.responseText || '';
                    if (text.length > lastResponseLength) {
                        onResponseChunk(text.slice(lastResponseLength));
                        lastResponseLength = text.length;
                    }
                }
                if (xhr.status >= 200 && xhr.status < 300) {
                    settle(resolve, { status: xhr.status, responseText: xhr.responseText || '', xhr });
                } else if (abortedBySignal || (signal && signal.aborted)) {
                    settle(reject, new DOMException('Aborted', 'AbortError'));
                } else if (xhr.status === 0) {
                    settle(reject, Object.assign(new Error('Network error'), { status: 0 }));
                } else {
                    settle(reject, Object.assign(new Error(`HTTP ${xhr.status}`), {
                        status: xhr.status,
                        responseText: xhr.responseText || ''
                    }));
                }
            }
        };

        xhr.onerror = () => {
            settle(reject, Object.assign(new Error('Network error'), { status: 0 }));
        };
        xhr.onabort = () => {
            settle(reject, new DOMException('Aborted', 'AbortError'));
        };
        xhr.ontimeout = () => {
            settle(reject, Object.assign(new Error('Network error'), { status: 0 }));
        };

        if (signal) {
            if (signal.aborted) {
                settle(reject, new DOMException('Aborted', 'AbortError'));
                return;
            }
            signal.addEventListener('abort', abortHandler, { once: true });
        }

        prepareFormData(formData);
        xhr.send(formData);
    });
}

function initTransfer() {
    if (btnErrorDownload) btnErrorDownload.onclick = () => downloadLocalAudio();
    if (btnUploadErrorDownload) btnUploadErrorDownload.onclick = () => downloadLocalAudio();
    el.abort.onclick = async () => {
        if (cancellationInProgress) return;
        cancellationInProgress = true;
        const taskId = currentTaskId;
        if (abortController) abortController.abort();
        el.stat.innerText = "停止しています...";
        el.abort.disabled = true;
        try {
            const cancellationSignal = new AbortController().signal;
            const requests = [];
            if (taskId) {
                requests.push(csrfFetch(`/api/tasks/${encodeURIComponent(taskId)}/cancel`, {
                    method: 'POST',
                    signal: cancellationSignal,
                }));
            }
            await Promise.allSettled(requests);
            currentTaskId = null;
            abortController = null;
            el.stat.innerText = "停止されました";
            showToast("処理を停止しました");
        } finally {
            cancellationInProgress = false;
            el.abort.disabled = false;
            el.abort.classList.add('d-none');
        }
    };
}
