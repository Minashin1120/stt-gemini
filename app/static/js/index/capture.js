// AudioWorklet によるPCM収集グラフと波形・入力レベルの可視化。

function downmixToMono(ch0, ch1) {
    if (!ch0) return ch0;
    if (!ch1) return ch0;
    const out = new Float32Array(ch0.length);
    for (let i = 0; i < out.length; i++) out[i] = (ch0[i] + ch1[i]) * 0.5;
    return out;
}

function appendCapturedPcm(chunk) {
    if (!chunk || !chunk.length) return;
    floatChunks.push(chunk);
    for (let i = 0; i < chunk.length; i++) {
        const value = Math.abs(chunk[i]);
        if (value > capturePeak) capturePeak = value;
    }
}

async function ensureCaptureWorkletModule(context) {
    if (!context.audioWorklet || typeof AudioWorkletNode === 'undefined') return false;
    if (captureWorkletContext !== context) {
        await context.audioWorklet.addModule('/static/js/pcm-capture-worklet.js?v=4');
        captureWorkletContext = context;
    }
    return true;
}

async function createPcmCaptureNode(context, inputChannels) {
    if (context.audioWorklet && typeof AudioWorkletNode !== 'undefined') {
        try {
            await ensureCaptureWorkletModule(context);
            const node = new AudioWorkletNode(context, 'stt-pcm-capture', {
                numberOfInputs: 1,
                numberOfOutputs: 1,
                outputChannelCount: [1],
                channelCount: inputChannels,
                channelCountMode: 'explicit'
            });
            node.port.onmessage = (event) => {
                const message = event.data || {};
                if (message.type === 'pcm' && message.buffer) {
                    appendCapturedPcm(new Float32Array(message.buffer));
                } else if (message.type === 'raw_pcm' && message.buffer) {
                    // リアルタイム配信用の生ステレオPCM。WSが開いていなければ黙って捨てる
                    // (ファイル録音=appendCapturedPcm側は常に継続するので安全網になる)。
                    if (grokLiveWs && grokLiveWs.readyState === WebSocket.OPEN) {
                        grokLiveWs.send(message.buffer);
                    }
                } else if (message.type === 'flushed' && captureFlushResolve) {
                    const resolve = captureFlushResolve;
                    captureFlushResolve = null;
                    resolve();
                }
            };
            captureBackend = 'audio-worklet';
            return node;
        } catch (error) {
            console.warn('[STT AudioWorklet fallback]', error);
        }
    }

    const node = context.createScriptProcessor(4096, inputChannels, 1);
    node.onaudioprocess = (event) => {
        if (isPaused) return;
        const ch0 = event.inputBuffer.getChannelData(0);
        const ch1 = event.inputBuffer.numberOfChannels > 1
            ? event.inputBuffer.getChannelData(1)
            : null;
        appendCapturedPcm(downmixToMono(ch0, ch1));
    };
    captureBackend = 'script-processor-fallback';
    return node;
}

function setCapturePaused(paused) {
    if (captureBackend === 'audio-worklet' && processor && processor.port) {
        processor.port.postMessage({ type: 'pause', value: !!paused });
    }
}

async function flushCaptureNode() {
    if (captureBackend !== 'audio-worklet' || !processor || !processor.port) return;
    await new Promise((resolve) => {
        let settled = false;
        const finish = () => {
            if (settled) return;
            settled = true;
            captureFlushResolve = null;
            resolve();
        };
        captureFlushResolve = finish;
        processor.port.postMessage({ type: 'flush' });
        window.setTimeout(finish, 500);
    });
}

function teardownCaptureGraph() {
    if (graphKeepAliveRecorder) {
        try {
            graphKeepAliveRecorder.ondataavailable = null;
            if (graphKeepAliveRecorder.state !== 'inactive') graphKeepAliveRecorder.stop();
        } catch (_) {}
        graphKeepAliveRecorder = null;
    }
    try { if (microphone) microphone.disconnect(); } catch (_) {}
    try { if (inputGain) inputGain.disconnect(); } catch (_) {}
    try {
        if (processor && processor.port) {
            processor.port.onmessage = null;
            processor.port.close();
        } else if (processor) {
            processor.onaudioprocess = null;
        }
    } catch (_) {}
    try { if (processor) processor.disconnect(); } catch (_) {}
    try { if (analyserNode) analyserNode.disconnect(); } catch (_) {}
    try { if (silentGain) silentGain.disconnect(); } catch (_) {}
    try { if (captureSink) captureSink.disconnect(); } catch (_) {}
    microphone = null;
    inputGain = null;
    processor = null;
    analyserNode = null;
    silentGain = null;
    captureSink = null;
    captureBackend = null;
    captureFlushResolve = null;
}

async function ensureAudioContextRunning() {
    if (!audioContext || audioContext.state === 'closed') {
        audioContext = new AudioContext();
    }
    if (audioContext.state === 'suspended') {
        try { await audioContext.resume(); } catch (_) {}
    }
    // 長時間の getUserMedia 後に suspended のまま残ることがある → 再生成
    if (audioContext.state !== 'running') {
        try { await audioContext.close(); } catch (_) {}
        audioContext = new AudioContext();
        try { await audioContext.resume(); } catch (_) {}
    }
    return audioContext.state;
}

function startVisualizer() {
    if (!analyserNode || !canvas) return;
    canvas.style.display = 'block';
    if (micLevelStatus) {
        micLevelStatus.classList.remove('d-none');
        micLevelStatus.textContent = '入力レベルを確認中…';
    }
    canvas.width = canvas.clientWidth || 300;
    canvas.height = canvas.clientHeight || 80;
    // Byte波形は約1/128刻みのため、AGCを切った生マイクの小さい信号が丸め落とされる。
    // Float波形を使い、タップなど短い入力も量子化で消えないようにする。
    const data = new Float32Array(analyserNode.fftSize);
    visPeakEma = 0.02;

    (function draw() {
        if (!isRecording || !analyserNode) return;
        requestAnimationFrame(draw);
        if (audioContext && audioContext.state === 'suspended') audioContext.resume();

        analyserNode.getFloatTimeDomainData(data);

        let peak = 0;
        let sumSq = 0;
        for (let i = 0; i < data.length; i++) {
            const d = data[i];
            const a = Math.abs(d);
            if (a > peak) peak = a;
            sumSq += d * d;
        }
        const peakN = peak;
        const rms = Math.sqrt(sumSq / data.length);
        visPeakEma = visPeakEma * 0.85 + Math.max(peakN, rms * 1.5) * 0.15;

        if (micLevelStatus && performance.now() - lastLevelUiUpdate > 250) {
            lastLevelUiUpdate = performance.now();
            const dbfs = rms > 0 ? 20 * Math.log10(rms) : -Infinity;
            if (rms < 0.001) {
                micLevelStatus.textContent = '入力レベル: ほぼ無音';
                micLevelStatus.className = 'small text-center text-danger mb-2';
            } else if (rms < 0.012) {
                micLevelStatus.textContent = `入力レベル: 小さめ (${Math.round(dbfs)} dBFS・停止後に補正)`;
                micLevelStatus.className = 'small text-center text-warning mb-2';
            } else if (peak > 0.92) {
                micLevelStatus.textContent = '入力レベル: 大きすぎます（端末を少し離してください）';
                micLevelStatus.className = 'small text-center text-danger mb-2';
            } else {
                micLevelStatus.textContent = `入力レベル: 適正 (${Math.round(dbfs)} dBFS)`;
                micLevelStatus.className = 'small text-center text-success mb-2';
            }
        }

        // 実レベルに近い感度（最大12倍）。以前の100倍は無音ノイズ床を「常時ノイズ」に見せていた
        let sensitivity = 8;
        if (visPeakEma > 0.02) {
            sensitivity = Math.min(12, Math.max(3, 0.55 / visPeakEma));
        } else if (visPeakEma > 0.005) {
            sensitivity = 10;
        }

        ctx.fillStyle = 'rgba(0,0,0,0.25)';
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        ctx.lineWidth = 2;
        const tm = document.documentElement.getAttribute('data-theme');
        ctx.strokeStyle = tm === 'gaming'
            ? `hsl(${Date.now() / 10 % 360},100%,50%)`
            : (getComputedStyle(document.body).getPropertyValue('--primary-color') || '#0d6efd');
        ctx.beginPath();
        let x = 0;
        const sw = canvas.width / data.length;
        for (let i = 0; i < data.length; i++) {
            let v = (data[i] * sensitivity) + 1.0;
            let y = v * canvas.height / 2;
            if (y < 0) y = 0;
            if (y > canvas.height) y = canvas.height;
            if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
            x += sw;
        }
        ctx.stroke();

        // 絶対レベルバー（正規化前の生レベル。短い=録音が小さい）
        const levelW = Math.min(canvas.width, Math.max(2, canvas.width * Math.min(1, visPeakEma * 4)));
        ctx.fillStyle = visPeakEma < 0.04 ? 'rgba(220,53,69,0.85)' : (visPeakEma < 0.15 ? 'rgba(255,193,7,0.9)' : 'rgba(25,135,84,0.85)');
        ctx.fillRect(0, canvas.height - 4, levelW, 4);
    })();
}
