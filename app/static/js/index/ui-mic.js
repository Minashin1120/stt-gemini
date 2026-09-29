// 画面要素の取得（`el` など）とマイク事前準備UI。

// UI Elements
let canvas;
let ctx;
let micLevelStatus;
let dropArea;
let fileInput;
let fileInfo;
let fileNameEl;
let fileSizeEl;
let removeFileBtn;
let btnUploadNew;
let btnUploadAppend;
let uploadProgressPanel;
let uploadProgressLabel;
let uploadProgressPercent;
let uploadProgressBar;
let uploadCancelRow;
let btnUploadCancel;
let uploadErrorActions;
let btnUploadErrorDownload;
let btnErrorDownload;
// 通信エラー時にローカル音声を保存できるよう保持（リロードすると消えるため）
let lastLocalAudioBlob = null;
let lastLocalAudioName = null;
// 失敗後に並列チャンクの進捗コールバックがUIを上書きしないようにする
let uploadUiActive = false;
// アップロード中のキャンセル状態
let uploadCancelled = false;
// チャンクアップロードの upload_id（キャンセル時にサーバー側のチャンクを掃除する）
let activeUploadId = null;
let bsDelModal;
let micProcessingErrorMessage;
let btnMicProcessingHelp;
let micProcessingHelp;
let bsMicProcessingErrorModal;
let thinkingOpen = false;
// 既存コード互換 (show/hide)
let thoughtCol;
let el;

function setMicProcessingHelpOpen(open) {
    if (!btnMicProcessingHelp || !micProcessingHelp) return;
    btnMicProcessingHelp.setAttribute('aria-expanded', open ? 'true' : 'false');
    btnMicProcessingHelp.innerHTML = open
        ? '<i class="bi bi-chevron-up me-1"></i>対処方法を閉じる'
        : '<i class="bi bi-question-circle me-1"></i>対処方法を確認';
    micProcessingHelp.classList.toggle('d-none', !open);
}

function showMicProcessingErrorDialog(message) {
    if (!bsMicProcessingErrorModal || !micProcessingErrorMessage) {
        showToast(message, true);
        return;
    }
    micProcessingErrorMessage.textContent = message;
    setMicProcessingHelpOpen(false);
    bsMicProcessingErrorModal.show();
}

function streamIsLive(stream) {
    const track = stream && stream.getAudioTracks && stream.getAudioTracks()[0];
    return !!track && track.readyState === 'live';
}

function releasePreparedMic() {
    if (audioStream && !isRecording) stopStreamTracks(audioStream);
    if (!isRecording) audioStream = null;
    preparedNoiseOn = null;
    if (!isRecording && audioContext) {
        try { audioContext.close(); } catch (_) {}
        audioContext = null;
    }
}

async function prepareMic() {
    if (isRecording) return;
    const noiseOn = !!(el.noise && el.noise.checked);
    if (preparedNoiseOn === noiseOn && streamIsLive(audioStream)) {
        el.stat.innerText = `マイク準備完了（ノイズ除去 ${noiseOn ? 'ON' : 'OFF'}）`;
        return;
    }

    releasePreparedMic();
    el.stat.innerText = "マイク準備中...";
    el.recNew.disabled = true;
    el.recAdd.disabled = true;
    try {
        await ensureAudioContextRunning();
        try { await ensureCaptureWorkletModule(audioContext); } catch (error) {
            console.warn('[STT AudioWorklet preload fallback]', error);
        }
        audioStream = await acquireMicStream(noiseOn);
        assertMicProcessingVerified(noiseOn, audioStream);
        preparedNoiseOn = noiseOn;
        el.stat.innerText = `マイク準備完了（ノイズ除去 ${noiseOn ? 'ON' : 'OFF'}）`;
        showToast('マイクの準備が完了しました');
    } catch (error) {
        releasePreparedMic();
        el.stat.innerText = "マイク準備エラー";
        if (error && error.isMicProcessingVerificationError) {
            showMicProcessingErrorDialog(error.message || String(error));
        } else {
            showToast(error.message || String(error), true);
        }
    } finally {
        el.recNew.disabled = false;
        el.recAdd.disabled = false;
    }
}

function initUiMic() {
    canvas = document.getElementById("audioVisualizer");
    ctx = canvas.getContext("2d");
    micLevelStatus = document.getElementById('micLevelStatus');
    dropArea = document.getElementById('dropArea');
    fileInput = document.getElementById('audioFileInput');
    fileInfo = document.getElementById('fileSelectionInfo');
    fileNameEl = document.getElementById('selectedFileName');
    fileSizeEl = document.getElementById('selectedFileSize');
    removeFileBtn = document.getElementById('btnRemoveFile');
    btnUploadNew = document.getElementById('btnUploadNew');
    btnUploadAppend = document.getElementById('btnUploadAppend');
    uploadProgressPanel = document.getElementById('uploadProgressPanel');
    uploadProgressLabel = document.getElementById('uploadProgressLabel');
    uploadProgressPercent = document.getElementById('uploadProgressPercent');
    uploadProgressBar = document.getElementById('uploadProgressBar');
    uploadCancelRow = document.getElementById('uploadCancelRow');
    btnUploadCancel = document.getElementById('btnUploadCancel');
    uploadErrorActions = document.getElementById('uploadErrorActions');
    btnUploadErrorDownload = document.getElementById('btnUploadErrorDownload');
    btnErrorDownload = document.getElementById('btnErrorDownload');
    bsDelModal = new bootstrap.Modal(document.getElementById('deleteModal'));
        const micProcessingErrorModalEl = document.getElementById('micProcessingErrorModal');
    micProcessingErrorMessage = document.getElementById('micProcessingErrorMessage');
    btnMicProcessingHelp = document.getElementById('btnMicProcessingHelp');
    micProcessingHelp = document.getElementById('micProcessingHelp');
    bsMicProcessingErrorModal = micProcessingErrorModalEl
        ? new bootstrap.Modal(micProcessingErrorModalEl)
        : null;
    if (btnMicProcessingHelp) {
        btnMicProcessingHelp.addEventListener('click', () => {
            setMicProcessingHelpOpen(btnMicProcessingHelp.getAttribute('aria-expanded') !== 'true');
        });
    }
    // 推論ボックス: Bootstrap Collapse の遷移ロックを避け、途中クリックで即反転
        const thinkingPanel = document.getElementById('collapseThinking');
        const thinkingBtn = document.getElementById('btnCollapse');
        const setThinkingOpen = function(open) {
        thinkingOpen = !!open;
        if (thinkingPanel) thinkingPanel.classList.toggle('is-open', thinkingOpen);
        if (thinkingBtn) {
            thinkingBtn.classList.toggle('collapsed', !thinkingOpen);
            thinkingBtn.setAttribute('aria-expanded', thinkingOpen ? 'true' : 'false');
        }
    };
    if (thinkingBtn) {
        thinkingBtn.addEventListener('click', function(e) {
            e.preventDefault();
            setThinkingOpen(!thinkingOpen);
        });
    }
    thoughtCol = {
        show: function() { setThinkingOpen(true); },
        hide: function() { setThinkingOpen(false); }
    };
    el = {
        recNew: document.getElementById('btnRecordNew'), recAdd: document.getElementById('btnRecordAppend'),
        pause: document.getElementById('btnPause'), stop: document.getElementById('btnStop'), cancel: document.getElementById('btnCancel'),
        copy: document.getElementById('btnCopy'), fix: document.getElementById('btnFixSpacing'),
        correctRephrase: document.getElementById('btnCorrectRephrase'),
        imp: document.getElementById('btnImprove'), stat: document.getElementById('status'),
        res: document.getElementById('resultText'), tho: document.getElementById('thoughtText'), procBar: document.getElementById('processingBar'),
        ins: document.getElementById('instructionInput'), think: document.getElementById('thinking_level'),
        noise: document.getElementById('noiseSuppression'), rephrase: document.getElementById('allowRephraseCorrection'), filler: document.getElementById('allowFillerRemoval'), useAudio: document.getElementById('useAudioForImprove'),
        re: document.getElementById('btnReanalyze'), delBtn: document.getElementById('btnOpenDel'),
        hist: document.getElementById('historyContainer'), reset: document.getElementById('btnReset'),
        abort: document.getElementById('btnAbort')
    };
    // el の初期化後に登録する。これより前に el を参照すると
    // DOMContentLoaded 全体が ReferenceError で停止し、録音ボタンと設定復元が動かなくなる。
    if (el.noise) {
        el.noise.addEventListener('change', () => {
            if (isRecording) return;
            prepareMic();
        });
    }
        const uploadTab = document.getElementById('upload-tab');
    if (uploadTab) {
        uploadTab.addEventListener('shown.bs.tab', () => {
            if (preparedNoiseOn === null || isRecording) return;
            releasePreparedMic();
            el.stat.innerText = "待機中";
        });
    }
    window.addEventListener('pagehide', () => {
        if (audioStream) stopStreamTracks(audioStream);
        if (audioContext) {
            try { audioContext.close(); } catch (_) {}
        }
    });
}
