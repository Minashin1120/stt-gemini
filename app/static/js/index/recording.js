// 録音の開始・一時停止・停止と、録音データの送信。

async function rec(append=false) {
    // Grok Liveは録音開始直後からAPIキーが必要。ほかのモデルと同じ停止時確認では、
    // キー未設定・不正時に録音中のリアルタイム字幕を復旧できないため先に確認する。
    if (getModel() === 'grok-live-transcribe') {
        const keyResult = await ensureApiKeyForModel(getModel(), false);
        if (!keyResult.proceed) {
            el.stat.innerText = "待機中";
            return;
        }
    }
    isAppendMode=append;
    if(!append) clearResultUiForNew();
    el.copy.disabled=true; el.stat.innerText="準備中...";
    const noiseOn = !!(el.noise && el.noise.checked);
    const usePreparedMic = preparedNoiseOn === noiseOn && streamIsLive(audioStream);
    if(audioStream && !usePreparedMic) {
        audioStream.getTracks().forEach(track => track.stop());
        audioStream = null;
    }
    preparedNoiseOn = null;
    teardownCaptureGraph();
    mediaRecorder = null;
    audioChunks = [];
    floatChunks = [];
    capturePeak = 0;
    if (grokLiveWs) { try { grokLiveWs.close(); } catch (_) {} grokLiveWs = null; }
    grokLiveText = ''; grokLiveInterim = ''; grokLiveFailed = false;
    el.res.classList.remove('grok-live-active');

    try {
        // ユーザージェスチャー内で AudioContext を用意
        await ensureAudioContextRunning();

        // チェックON = ノイズ除去する / OFF = EC/NS/AGC/voice isolation をすべて切る
        recordNoiseSuppression = noiseOn;
        if (!usePreparedMic) audioStream = await acquireMicStream(noiseOn);
        const verifiedMic = assertMicProcessingVerified(noiseOn, audioStream);

        // getUserMedia が長引くと suspended になりうる → 再確保
        const ctxState = await ensureAudioContextRunning();
        console.log('[STT AudioContext]', ctxState);

        isRecording = true;
        isPaused = false;
        if (el.noise) el.noise.disabled = true;
        recordFormat = document.querySelector('input[name="format"]:checked').value;

        // mic → inputGain → Analyser → AudioWorklet → MediaStreamDestination の直列グラフ
        // 重要: audioContext.destination（実スピーカー）へは絶対に繋がない。
        // モバイル Chrome/Android は録音中の再生があるとフルデュプレックス経路になり、
        // プラットフォーム側の AEC/NS が constraints と無関係に効く（gain 0.0001 でも起きうる）。
        microphone = audioContext.createMediaStreamSource(audioStream);
        inputGain = audioContext.createGain();
        inputGain.gain.value = 1.0;

        analyserNode = audioContext.createAnalyser();
        analyserNode.fftSize = 2048;
        analyserNode.smoothingTimeConstant = 0.25;
        // 入力チャンネルはストリームに合わせる（2ch時はワークレット/フォールバックで
        // 両チャンネルを均等にダウンミックスしてモノラルに収束させる）
        const inCh = Math.min(2, Math.max(1, (audioStream.getAudioTracks()[0]
            && audioStream.getAudioTracks()[0].getSettings
            && audioStream.getAudioTracks()[0].getSettings().channelCount) || 1));
        processor = await createPcmCaptureNode(audioContext, inCh);
        captureSink = audioContext.createMediaStreamDestination();
        // silentGain は後方互換の切断用に null のまま（destination へは接続しない）
        silentGain = null;
        audioChunks = [];
        floatChunks = [];
        capturePeak = 0;

        microphone.connect(inputGain);
        // Analyserを録音経路上に置く。出力未接続の並列枝はモバイルChromeで
        // レンダリング対象から外され、波形が更新されないことがある。
        inputGain.connect(analyserNode);
        analyserNode.connect(processor);
        // スピーカー出力ゼロ: MediaStreamDestination のみへ接続する。
        processor.connect(captureSink);

        // MediaStreamDestinationを読むconsumerが無いと、モバイルChromeがグラフ全体を
        // 休止する場合がある。MediaRecorderで内部ストリームを消費し、データは捨てる。
        // audioContext.destinationには接続しないため、スピーカー経路/AECは起動しない。
        if (typeof MediaRecorder !== 'undefined') {
            try {
                graphKeepAliveRecorder = new MediaRecorder(captureSink.stream);
                graphKeepAliveRecorder.ondataavailable = () => {};
                graphKeepAliveRecorder.start(1000);
            } catch (keepAliveError) {
                graphKeepAliveRecorder = null;
                console.warn('[STT graph keepalive unavailable]', keepAliveError);
            }
        }

        if (getModel() === 'grok-live-transcribe' && captureBackend === 'audio-worklet') {
            startGrokLiveSession(inCh, audioContext.sampleRate);
        }

        startVisualizer();

        el.recNew.disabled=el.recAdd.disabled=true; el.stop.disabled=el.pause.disabled=el.cancel.disabled=false;
        const mic = verifiedMic;
        lastMicSettings = mic;
        const effNs = mic.noiseSuppression;
        const mismatch = !!mic.mismatch || (!noiseOn && !mic.processingFullyOff);
        const routing = mic.routing || {};
        const noiseLabel = noiseOn
            ? '<span class="badge bg-primary ms-1">ノイズ除去: ON</span>'
            : '<span class="badge bg-secondary ms-1">ノイズ除去: OFF</span>';
        let effLabel = '';
        if (!noiseOn && mic.processingFullyOff) {
            effLabel = '<span class="badge bg-success ms-1">Chrome処理: OFF確認</span>';
        } else if (typeof effNs === 'boolean') {
            effLabel = `<span class="badge ${mismatch ? 'bg-warning text-dark' : 'bg-dark'} ms-1">Chrome NS: ${effNs ? 'ON' : 'OFF'}</span>`;
        } else if (!noiseOn) {
            effLabel = '<span class="badge bg-warning text-dark ms-1">Chrome処理: 確認不可</span>';
        }
        const agcLabel = (typeof mic.autoGainControl === 'boolean')
            ? `<span class="badge bg-dark ms-1">AGC: ${mic.autoGainControl ? 'ON' : 'OFF'}</span>`
            : '';
        const ecLabel = (typeof mic.echoCancellation === 'boolean')
            ? `<span class="badge bg-dark ms-1">EC: ${mic.echoCancellation ? 'ON' : 'OFF'}</span>`
            : '';
        const fmtLabel = recordFormat === 'wav'
            ? '<span class="badge bg-info text-dark ms-1">WAV(PCM)</span>'
            : '<span class="badge bg-info text-dark ms-1">MP3 192k</span>';
        const captureLabel = captureBackend === 'audio-worklet'
            ? '<span class="badge bg-success ms-1">安定録音</span>'
            : '<span class="badge bg-warning text-dark ms-1">互換録音</span>';
        const chanLabel = (inCh > 1)
            ? '<span class="badge bg-info text-dark ms-1">2ch→モノミックス</span>'
            : '<span class="badge bg-secondary ms-1">マイク入力: 1ch</span>';
        const micName = (routing.selectedLabel || routing.requestedLabel || '').trim();
        const micLabelBadge = micName
            ? `<span class="badge bg-dark ms-1" title="${escapeHtml(micName)}">マイク: ${escapeHtml(micName.length > 14 ? micName.slice(0, 14) + '…' : micName)}</span>`
            : '';
        let micRouteLabel = '';
        if (routing.mode === 'built-in-exact-verified') {
            micRouteLabel = '<span class="badge bg-success ms-1">内蔵マイク: 固定確認</span>';
        } else if (routing.mode === 'built-in-exact-requested') {
            micRouteLabel = '<span class="badge bg-info text-dark ms-1">内蔵マイク: 固定指定</span>';
        } else if (routing.mobileTarget) {
            micRouteLabel = '<span class="badge bg-warning text-dark ms-1">マイク: 端末既定</span>';
        }
        el.stat.innerHTML=`<span class="recording-indicator">● 録音中...</span> ${micRouteLabel} ${noiseLabel} ${effLabel} ${agcLabel} ${ecLabel} ${fmtLabel} ${captureLabel} ${chanLabel} ${micLabelBadge}`;
        if (routing.warning) {
            showToast(routing.warning, true);
        } else if (mismatch) {
            showToast(noiseOn
                ? '端末がノイズ除去ONを拒否している可能性があります'
                : 'Chromeで全処理OFFを確認できませんでした。端末側処理が残る可能性があります', true);
        } else if (!noiseOn && mic.processingFullyOff) {
            showToast('録音開始（Chrome音声処理OFFを確認）');
        } else {
            showToast(noiseOn ? '録音開始（ノイズ除去ON）' : '録音開始（ノイズ除去OFF）');
        }
    } catch(e){
        isRecording=false;
        if (el.noise) el.noise.disabled = false;
        teardownCaptureGraph();
        if (audioStream) { audioStream.getTracks().forEach(t=>t.stop()); audioStream=null; }
        el.stat.innerText="エラー";
        if (e && e.isMicProcessingVerificationError) {
            showMicProcessingErrorDialog(e.message || String(e));
        } else {
            showToast(e.message || String(e), true);
        }
    }
}

function initRecording() {
    el.recNew.onclick=()=>rec(false);
el.recAdd.onclick=()=>rec(true);
    el.pause.onclick=()=>{
        isPaused=!isPaused;
        setCapturePaused(isPaused);
        if(isPaused){
            if(mediaRecorder) mediaRecorder.pause();
            el.stat.innerText="一時停止";
            el.pause.innerHTML='<i class="bi bi-play-fill"></i>';
        } else {
            if(mediaRecorder) mediaRecorder.resume();
            const noiseOn = recordNoiseSuppression;
            const noiseLabel = noiseOn
                ? '<span class="badge bg-primary ms-1">ノイズ除去: ON</span>'
                : '<span class="badge bg-secondary ms-1">ノイズ除去: OFF</span>';
            el.stat.innerHTML=`<span class="recording-indicator">● 録音中...</span> ${noiseLabel}`;
            el.pause.innerHTML='<i class="bi bi-pause-fill"></i>';
        }
    };
    el.cancel.onclick=()=>{
        isRecording=false; isPaused=false;
        if (el.noise) el.noise.disabled = false;
        if(mediaRecorder){ mediaRecorder.onstop = null; try { mediaRecorder.stop(); } catch(_){} mediaRecorder=null; }
        teardownCaptureGraph();
        if(audioStream){ audioStream.getTracks().forEach(t=>t.stop()); audioStream=null; }
        if(audioContext){ try { audioContext.close(); } catch(_){} audioContext=null; }
        audioChunks=[]; floatChunks=[]; capturePeak=0;
        if (grokLiveWs) {
            try { grokLiveWs.close(); } catch (_) {}
            grokLiveWs = null;
            el.res.value = initText;
        }
        grokLiveText = ''; grokLiveInterim = ''; grokLiveFailed = false;
        el.res.classList.remove('grok-live-active');
        if (micLevelStatus) micLevelStatus.classList.add('d-none');
        el.recNew.disabled=el.recAdd.disabled=false; el.stop.disabled=el.pause.disabled=el.cancel.disabled=true;
        el.pause.innerHTML='<i class="bi bi-pause-fill"></i>';
        el.stat.innerText="キャンセル"; showToast("キャンセルしました");
    };
    el.stop.onclick=async()=>{
        isRecording=false;
        const wasGrokLive = getModel() === 'grok-live-transcribe';
        setCapturePaused(true);
        await flushCaptureNode();
        if (wasGrokLive) await finishGrokLiveSession();
        if (el.noise) el.noise.disabled = false;
        el.recNew.disabled=el.recAdd.disabled=false; el.stop.disabled=el.pause.disabled=el.cancel.disabled=true;
        el.pause.innerHTML='<i class="bi bi-pause-fill"></i>';
        el.stat.innerText="解析中...";
        const sampleRate = audioContext ? audioContext.sampleRate : 48000;
        const chunks = floatChunks;
        const fmt = recordFormat;
        const noiseOn = recordNoiseSuppression;
        teardownCaptureGraph();
        if (micLevelStatus) micLevelStatus.classList.add('d-none');

        if (audioStream) { audioStream.getTracks().forEach(t=>t.stop()); audioStream=null; }
        if (audioContext) { try { audioContext.close(); } catch(_){} audioContext=null; }

        if (chunks && chunks.length > 0) {
            try {
                const agcOff = !!(lastMicSettings && lastMicSettings.autoGainControl === false);
                const { blob, gain, peakBefore } = encodeNormalizedRecording(
                    chunks, sampleRate, fmt === 'wav' ? 'wav' : 'mp3', { agcOff, noiseOn }
                );
                floatChunks = [];
                capturePeak = 0;
                lastMicSettings = null;
                console.log('[STT] applied makeup gain', gain.toFixed(2), 'rawPeak', peakBefore, 'agcOff', agcOff);
                const recName = fmt === 'wav' ? 'rec.wav' : 'rec.mp3';
                // transcript.doneがタイムアウト等で届かない/空のことがあるため、
                // その場合でも未確定のまま表示されていたgrokLiveInterimを最終テキストに含める
                // (でないとリアルタイムでは見えていたのに保存結果が空になってしまう)。
                const grokLiveFinalText = grokLiveText + (grokLiveInterim ? (grokLiveText ? '\n' : '') + grokLiveInterim : '');
                if (wasGrokLive && !grokLiveFailed && grokLiveFinalText) {
                    finalizeLiveGrok(blob, recName, grokLiveFinalText);
                } else {
                    // WSが使えなかった/テキストが取れなかった場合は通常のバッチアップロードにフォールバック。
                    // model='grok-live-transcribe'のまま送ってよい(サーバー側/transcribeがgrok-sttと
                    // 同じバッチ処理として扱う)。ensureApiKeyForModel でユーザーが別モデルに切り替えた
                    // 場合もその選択がそのまま使われる。
                    // captureBackend='audio-worklet'でない場合はそもそもライブセッションを開始していない
                    // (el.resは未変更のまま)ため、その場合はここでinitTextへ戻してはいけない。
                    if (wasGrokLive && captureBackend === 'audio-worklet') el.res.value = initText;
                    upl(blob, recName);
                }
            } catch (err) {
                floatChunks = [];
                lastMicSettings = null;
                el.stat.innerText = "エラー";
                showToast(err.message || String(err), true);
            }
        } else if (mediaRecorder) {
            mediaRecorder.stop();
        } else {
            floatChunks = [];
            el.stat.innerText="エラー";
            showToast("録音データがありません", true);
        }
    };
}
