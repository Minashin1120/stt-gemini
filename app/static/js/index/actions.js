// 再分析・AI改善・間隔修正・言い直し修正などの結果操作ボタン。

async function upl(b,n){
    const model = getModel();
    const result = await ensureApiKeyForModel(model, true, b, n);
    if (!result.proceed) { el.stat.innerText = "待機中"; return; }
    const useModel = result.model;
    rememberLocalAudio(b, n);
    hideErrorDownloadButton();
    if (isBatchMode(useModel)) {
        el.stat.innerText = "Batchに投入中...";
        const ok = await submitBatchJob({ action: 'transcribe', model: useModel }, b, n);
        el.stat.innerText = ok ? "Batchに投入しました" : "エラー";
        if (!ok) showErrorDownloadButton(true);
        return;
    }
    // 新規録音の送信時は再確認でテキストをクリア（APIキー入力待ちの間に編集された場合など）
    if (!isAppendMode) clearResultUiForNew();
    el.stat.innerText = "アップロード中...";
    abortController = new AbortController();
    const fd=new FormData(); fd.append('audio_file',b,n); fd.append('thinking_level',el.think.value); fd.append('model', useModel); fd.append('allow_rephrase_correction', el.rephrase && el.rephrase.checked ? '1' : '0'); fd.append('allow_filler_removal', el.filler && el.filler.checked ? '1' : '0'); fd.append('is_append', isAppendMode ? '1' : '0');
    try{
        const r=await csrfFetch('/transcribe',{
            method:'POST',
            body:fd,
            signal: abortController.signal
        });
        if(!r.ok){
            const err = await r.json().catch(() => ({}));
            if (!isAppendMode) loadHistory();
            const msg = err.error || "エラーが発生しました";
            el.stat.innerText = "エラー";
            showErrorDownloadButton(true);
            showToast(msg, true);
            return;
        }
        await handleStreamResponse(r);
    }catch(e){
        if(e.name === 'AbortError') {
            el.stat.innerText = "停止されました";
            showErrorDownloadButton(true);
            return;
        }
        if (!isAppendMode) loadHistory();
        el.stat.innerText = "通信エラー";
        showErrorDownloadButton(true);
        showToast("通信エラー", true);
    }
}

/** Grok Live: WebSocketで受信済みの確定テキストを音声と一緒にサーバーへ送って保存する。
 * 既存のAI呼び出し(/transcribe)を経由しない: 文字起こしは既にWebSocket上で完了しているため。 */
async function finalizeLiveGrok(b, n, text) {
    rememberLocalAudio(b, n);
    hideErrorDownloadButton();
    el.stat.innerText = "保存中...";
    const fd = new FormData();
    fd.append('audio_file', b, n);
    fd.append('text', text);
    fd.append('is_append', isAppendMode ? '1' : '0');
    try {
        const r = await csrfFetch('/transcribe_live_finalize', { method: 'POST', body: fd });
        if (!r.ok) {
            const err = await r.json().catch(() => ({}));
            if (!isAppendMode) loadHistory();
            el.stat.innerText = "エラー";
            showErrorDownloadButton(true);
            showToast(err.error || "エラーが発生しました", true);
            return;
        }
        const data = await r.json();
        el.res.value = initText + (data.text || '');
        el.stat.innerText = "完了";
        el.copy.disabled = el.re.disabled = el.delBtn.disabled = false;
        syncPostprocessButtons();
        showToast("完了");
        loadHistory();
    } catch (e) {
        el.stat.innerText = "通信エラー";
        showErrorDownloadButton(true);
        showToast("通信エラー", true);
    }
}

// 結果テキストがあるときは後処理ボタンを有効化（手編集・貼り付けにも追従）
function syncPostprocessButtons() {
    const hasText = !!(el.res && el.res.value.trim());
    const isLite = getModel() === 'gemini-3.1-flash-lite' || getModel() === 'gemini-3.5-flash-lite';
    if (el.correctRephrase) { el.correctRephrase.disabled = !hasText; }
    if (el.fix && isLite) { el.fix.disabled = !hasText; }
}

function initActions() {
    el.re.onclick=async()=>{
        const model = getModel();
        const result = await ensureApiKeyForModel(model, false);
        if (!result.proceed) { el.stat.innerText = "待機中"; return; }
        const useModel = result.model;
        isAppendMode=false;
        if (isBatchMode(useModel)) {
            el.stat.innerText = "Batchに投入中...";
            const ok = await submitBatchJob({ action: 'reanalyze', model: useModel });
            el.stat.innerText = ok ? "Batchに投入しました" : "エラー";
            return;
        }
        el.stat.innerText="再分析中...";
        abortController = new AbortController();
        try{ const r=await csrfFetch('/reanalyze',{
            method:'POST',
            headers:{'Content-Type':'application/json'},
            body:JSON.stringify({thinking_level:el.think.value,model:useModel,allow_rephrase_correction:el.rephrase && el.rephrase.checked,allow_filler_removal:el.filler && el.filler.checked}),
            signal: abortController.signal
        });
        if(!r.ok){ showToast("エラー",true); return; } await handleStreamResponse(r); }catch(e){ if(e.name!=='AbortError') showToast("失敗",true); }
    };
    el.imp.onclick=async()=>{
        const t=el.res.value, i=el.ins.value; if(!t||!i){showToast("入力してください",true);return;}
        isAppendMode=false;
        if (isBatchMode(getModel())) {
            el.imp.disabled = true;
            const ok = await submitBatchJob({ action: 'improve', model: getModel(), text: t, instruction: i, use_audio: el.useAudio.checked ? '1' : '0' });
            el.imp.disabled = false;
            if (ok) el.ins.value = "";
            return;
        }
        el.stat.innerText="改善中..."; el.imp.disabled=true;
        abortController = new AbortController();
        try{
            const r=await csrfFetch('/improve',{
                method:'POST',
                headers:{'Content-Type':'application/json'},
                body:JSON.stringify({text:t,instruction:i,thinking_level:el.think.value,model:getModel(),use_audio:el.useAudio.checked}),
                signal: abortController.signal
            });
            if(!r.ok){
                const err = await r.json().catch(() => ({}));
                showToast(err.error || "エラーが発生しました", true);
                el.stat.innerText = "エラー";
                return;
            }
            await handleStreamResponse(r);
            el.ins.value="";
        } catch (e) {
            if (e.name !== 'AbortError') showToast("通信エラー", true);
        } finally{ el.imp.disabled=false; }
    };
    el.fix.onclick=async()=>{
        const t=el.res.value; if(!t){showToast("テキストがありません",true);return;}
        const fixInstruction = "以下の処理を順に実行してください：\n1. 不自然なスペース（空白）をすべて除去してください。単語間の適切なスペース（例：英単語の区切りなど）は保持してください。\n2. 句読点（。、）が一文も使われていないなど、句読点が完全に欠落している場合のみ、適切な句読点を補ってください。句読点が一部でも使われている場合は、句読点の修正は行わないでください。\n表記・言い回し・文体には一切変更を加えず、修正後のテキストのみを出力してください。説明や接頭辞・接尾辞は一切付けないでください。"
        isAppendMode=false; el.stat.innerText="間隔修正中..."; el.fix.disabled=true;
        abortController = new AbortController();
        try{
            const r=await csrfFetch('/improve',{
                method:'POST',
                headers:{'Content-Type':'application/json'},
                body:JSON.stringify({text:t,instruction:fixInstruction,thinking_level:el.think.value,model:getModel(),use_audio:false}),
                signal: abortController.signal
            });
            if(!r.ok){
                const err = await r.json().catch(() => ({}));
                showToast(err.error || "エラーが発生しました", true);
                el.stat.innerText = "エラー";
                return;
            }
            await handleStreamResponse(r);
        } catch (e) {
            if (e.name !== 'AbortError') showToast("通信エラー", true);
        } finally{ el.fix.disabled=false; }
    };
    if (el.res) el.res.addEventListener('input', syncPostprocessButtons);
    // テキストのみの言い直し修正（音声・履歴なし）。Flash-Lite 等で音声経路の言い直しが弱い場合の後処理用。
    if (el.correctRephrase) el.correctRephrase.onclick = async () => {
        const t = el.res.value;
        if (!t || !t.trim()) { showToast("テキストがありません", true); return; }
        const model = getModel();
        const result = await ensureApiKeyForModel(model, false);
        if (!result.proceed) { el.stat.innerText = "待機中"; return; }
        const useModel = result.model;
        isAppendMode = false;
        el.stat.innerText = "言い直し修正中...";
        el.correctRephrase.disabled = true;
        abortController = new AbortController();
        try {
            const r = await csrfFetch('/correct_rephrase', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    text: t,
                    thinking_level: el.think.value,
                    model: useModel
                }),
                signal: abortController.signal
            });
            if (!r.ok) {
                const err = await r.json().catch(() => ({}));
                showToast(err.error || "エラーが発生しました", true);
                el.stat.innerText = "エラー";
                syncPostprocessButtons();
                return;
            }
            await handleStreamResponse(r);
        } catch (e) {
            if (e.name !== 'AbortError') showToast("通信エラー", true);
        } finally {
            syncPostprocessButtons();
        }
    };
    el.ins.onkeypress=e=>{if(e.key==='Enter')el.imp.click();};
    document.getElementById('confirmDeleteBtn').onclick=async()=>{
        bsDelModal.hide();
        try{ const r=await csrfFetch('/delete_audio',{method:'POST'}); if(r.ok){ showToast("削除しました"); el.stat.innerText="削除済み"; el.re.disabled=el.delBtn.disabled=true; } }catch(e){}
    };
    el.copy.onclick=()=>{ navigator.clipboard.writeText(el.res.value).then(()=>showToast("コピーしました")); };
}
