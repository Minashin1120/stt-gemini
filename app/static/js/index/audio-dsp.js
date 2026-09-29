// 録音PCMの正規化（ゲイン・リミッタ）と WAV / MP3 エンコード。

function floatTo16BitPCM(float32Array, gain) {
    const g = (typeof gain === 'number' && isFinite(gain)) ? gain : 1;
    const d = new Int16Array(float32Array.length);
    for (let i = 0; i < float32Array.length; i++) {
        let s = float32Array[i] * g;
        s = Math.max(-1, Math.min(1, s));
        d[i] = s < 0 ? (s * 0x8000) : (s * 0x7FFF);
    }
    return d;
}

function percentile(sortedValues, ratio) {
    if (!sortedValues.length) return 0;
    const index = Math.min(sortedValues.length - 1, Math.floor(sortedValues.length * ratio));
    return sortedValues[index];
}

/**
 * 20msごとのRMS分布を計測する。
 * 最大ピークだけを見ると、端末を触った一瞬の衝撃音によって小声の補正量が不足するため、
 * 定常ノイズ床より明確に大きいフレーム群から発話レベルを求める。
 */
function measureRecordingLevels(float32Chunks, sampleRate) {
    let peak = 0;
    const frameLength = Math.max(128, Math.round((sampleRate || 48000) * 0.02));
    const frameRms = [];
    let frameSum = 0;
    let frameSamples = 0;
    for (let c = 0; c < float32Chunks.length; c++) {
        const ch = float32Chunks[c];
        for (let i = 0; i < ch.length; i++) {
            const sample = ch[i];
            const a = Math.abs(sample);
            if (a > peak) peak = a;
            frameSum += sample * sample;
            frameSamples++;
            if (frameSamples === frameLength) {
                frameRms.push(Math.sqrt(frameSum / frameSamples));
                frameSum = 0;
                frameSamples = 0;
            }
        }
    }
    if (frameSamples) frameRms.push(Math.sqrt(frameSum / frameSamples));
    frameRms.sort((a, b) => a - b);

    const noiseRms = percentile(frameRms, 0.2);
    const speechThreshold = Math.max(0.0008, noiseRms * 1.8);
    const speechFrames = frameRms.filter(value => value >= speechThreshold);
    const speechRms = speechFrames.length >= 3
        ? percentile(speechFrames, 0.65)
        : percentile(frameRms, 0.9);
    return { peak, noiseRms, speechRms, frameCount: frameRms.length };
}

function computeMakeupGain(levels, opts) {
    if (!levels || !(levels.speechRms > 1e-6)) return 1;
    const noiseOn = !!(opts && opts.noiseOn);
    const targetRms = 0.09;
    const maxGain = noiseOn ? 6 : ((opts && opts.agcOff) ? 12 : 8);
    return Math.min(maxGain, Math.max(1, targetRms / levels.speechRms));
}

function softLimit(sample) {
    const sign = sample < 0 ? -1 : 1;
    const value = Math.abs(sample);
    if (value <= 0.82) return sample;
    return sign * (0.82 + 0.18 * (1 - Math.exp(-(value - 0.82) / 0.18)));
}

function floatChunksToInt16(float32Chunks, gain) {
    const out = [];
    for (let c = 0; c < float32Chunks.length; c++) {
        const input = float32Chunks[c];
        const limited = new Float32Array(input.length);
        for (let i = 0; i < input.length; i++) {
            limited[i] = softLimit(input[i] * gain);
        }
        out.push(floatTo16BitPCM(limited, 1));
    }
    return out;
}

/** Build a mono 16-bit PCM WAV Blob from Int16Array chunks. */
function encodeWavBlob(int16Chunks, sampleRate) {
    let totalSamples = 0;
    for (let i = 0; i < int16Chunks.length; i++) totalSamples += int16Chunks[i].length;
    const dataSize = totalSamples * 2;
    const buffer = new ArrayBuffer(44 + dataSize);
    const view = new DataView(buffer);
    const writeStr = (offset, str) => {
        for (let i = 0; i < str.length; i++) view.setUint8(offset + i, str.charCodeAt(i));
    };
    writeStr(0, 'RIFF');
    view.setUint32(4, 36 + dataSize, true);
    writeStr(8, 'WAVE');
    writeStr(12, 'fmt ');
    view.setUint32(16, 16, true);
    view.setUint16(20, 1, true);
    view.setUint16(22, 1, true);
    view.setUint32(24, sampleRate, true);
    view.setUint32(28, sampleRate * 2, true);
    view.setUint16(32, 2, true);
    view.setUint16(34, 16, true);
    writeStr(36, 'data');
    view.setUint32(40, dataSize, true);
    let offset = 44;
    for (let c = 0; c < int16Chunks.length; c++) {
        const chunk = int16Chunks[c];
        for (let i = 0; i < chunk.length; i++, offset += 2) {
            view.setInt16(offset, chunk[i], true);
        }
    }
    return new Blob([buffer], { type: 'audio/wav' });
}

function encodeMp3Blob(int16Chunks, sampleRate) {
    const encoder = new lamejs.Mp3Encoder(1, sampleRate, 192);
    const parts = [];
    for (let i = 0; i < int16Chunks.length; i++) {
        const encoded = encoder.encodeBuffer(int16Chunks[i]);
        if (encoded.length > 0) parts.push(encoded);
    }
    const flush = encoder.flush();
    if (flush.length > 0) parts.push(flush);
    return new Blob(parts, { type: 'audio/mp3' });
}

/**
 * float PCM チャンクからレベル補正済み WAV/MP3 を生成。
 * 戻り値: { blob, gain, peakBefore }
 * @param {Object} [opts] - Optional settings such as agcOff.
 */
function encodeNormalizedRecording(float32Chunks, sampleRate, format, opts) {
    const levels = measureRecordingLevels(float32Chunks, sampleRate);
    const gain = computeMakeupGain(levels, opts);
    console.log('[STT level makeup]', {
        peakBefore: levels.peak,
        speechRms: levels.speechRms,
        noiseRms: levels.noiseRms,
        gain,
        peakAfterApprox: Math.min(1, levels.peak * gain),
        agcOff: !!(opts && opts.agcOff),
        noiseOn: !!(opts && opts.noiseOn),
        format,
        sampleRate
    });
    const int16 = floatChunksToInt16(float32Chunks, gain);
    if (format === 'wav') {
        return { blob: encodeWavBlob(int16, sampleRate), gain, peakBefore: levels.peak };
    }
    return { blob: encodeMp3Blob(int16, sampleRate), gain, peakBefore: levels.peak };
}
