// Grok Live（WebSocket リアルタイム文字起こし）のクライアント。

function startGrokLiveSession(channels, sampleRate) {
    grokLiveText = '';
    grokLiveInterim = '';
    grokLiveFailed = false;
    grokLiveChannels = channels;
    initText = (isAppendMode && el.res.value.trim()) ? el.res.value.trim() + "\n\n" : "";
    const qs = new URLSearchParams({
        sample_rate: String(Math.round(sampleRate) || 16000),
        encoding: 'pcm',
        interim_results: 'true',
        endpointing: '400',
        multichannel: channels > 1 ? 'true' : 'false',
        channels: String(channels),
    });
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    try {
        grokLiveWs = new WebSocket(`${proto}://${location.host}/ws/grok_live?${qs.toString()}`);
    } catch (_) {
        grokLiveWs = null;
        grokLiveFailed = true;
        return;
    }
    grokLiveWs.binaryType = 'arraybuffer';
    grokLiveWs.onopen = () => {
        if (processor && processor.port) {
            processor.port.postMessage({ type: 'raw_stereo', value: true, channels });
        }
        el.res.value = initText;
        el.res.classList.add('grok-live-active');
    };
    grokLiveWs.onmessage = (ev) => {
        let d;
        try { d = JSON.parse(ev.data); } catch (_) { return; }
        const chIdx = d.channel_index || 0;
        // 2マイクは同じ音源を別々に拾っているだけなので、表示・確定はchannel 0のみを使う
        // (両方使うと同じ内容が二重に表示・確定されてしまう)。
        if (chIdx !== 0) return;
        if (d.type === 'transcript.partial') {
            if (d.is_final && d.speech_final && d.text) {
                grokLiveText += (grokLiveText ? '\n' : '') + d.text;
                grokLiveInterim = '';
                el.res.value = initText + grokLiveText;
            } else {
                grokLiveInterim = d.text || '';
                el.res.value = initText + grokLiveText + (grokLiveText ? '\n' : '') + grokLiveInterim;
            }
            el.res.scrollTop = el.res.scrollHeight;
        } else if (d.type === 'transcript.done') {
            // transcript.doneはセッション終了時に送られる、そのチャンネルの確定済み全文。
            // speech_final境界の後に発話された末尾の言葉もここに含まれるため、
            // 積み上げてきたテキストより常に優先する(権威あるソース)。
            // ただしタイムアウト等でtextが空/未着のこともあるため、その場合はgrokLiveTextを
            // 保持したままにする(下のstop.onclickでgrokLiveInterimを最終フォールバックとして使う)。
            if (d.text) { grokLiveText = d.text; grokLiveInterim = ''; }
            el.res.value = initText + grokLiveText;
        } else if (d.type === 'error') {
            grokLiveFailed = true;
        }
    };
    grokLiveWs.onerror = () => { grokLiveFailed = true; };
    grokLiveWs.onclose = () => {
        if (processor && processor.port) {
            try { processor.port.postMessage({ type: 'raw_stereo', value: false }); } catch (_) {}
        }
    };
}

async function finishGrokLiveSession() {
    if (!grokLiveWs) return;
    const ws = grokLiveWs;
    if (ws.readyState === WebSocket.OPEN) {
        try { ws.send(JSON.stringify({ type: 'audio.done' })); } catch (_) {}
        await new Promise((resolve) => {
            let done = false;
            const finish = () => { if (done) return; done = true; resolve(); };
            ws.addEventListener('close', finish, { once: true });
            // サーバー側の中継タイムアウト(8秒)より少し長めに待ち、
            // 末尾の未確定発話に対するtranscript.doneが届く猶予を確保する
            window.setTimeout(finish, 9000);
        });
    } else {
        try { ws.close(); } catch (_) {}
    }
    if (grokLiveWs === ws) grokLiveWs = null;
    el.res.classList.remove('grok-live-active');
}
