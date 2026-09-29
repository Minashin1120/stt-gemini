// マイク取得（内蔵マイク固定・音声処理制約の検証）。

const EXTERNAL_MIC_LABEL_PATTERN = /(?:bluetooth|wireless|head[\s-]?(?:set|phone)s?|ear[\s-]?(?:bud|phone)s?|airpods?|(?:galaxy|pixel)[\s-]?buds?|hands[\s-]?free|\bsco\b|\ba2dp\b|\busb\b|wired|hearing[\s-]?aid|ブルートゥース|ワイヤレス|ヘッドセット|ヘッドホン|イヤホン|イヤフォン|有線|補聴器)/i;
const BUILTIN_MIC_LABEL_PATTERN = /(?:built[\s-]?in|internal|handset|speaker[\s-]?phone|phone(?:'s)?[\s-]?(?:mic|microphone)|microphone[\s-]?array|device[\s-]?(?:mic|microphone)|内蔵|本体|端末|スマートフォン|スピーカーフォン)/i;

/**
 * マイク取得（ノイズ除去 ON/OFF）
 *
 * Chromium/Android は、初回のストリーム生成時に音声効果が一つでも有効だと
 * VOICE_COMMUNICATION、すべて無効なら CAMCORDER の録音プリセットを選ぶ。
 * 取得後の applyConstraints では端末側プリセットが切り替わらない可能性があるため使わず、
 * 初回 getUserMedia に exact 制約を渡して確定させる。必須制約に対応しない端末のみ
 * 通常の Boolean 制約へフォールバックする。
 */
function stopStreamTracks(stream) {
    if (!stream) return;
    try { stream.getTracks().forEach(t => t.stop()); } catch (_) {}
}

function readMicSettings(track) {
    if (!track || typeof track.getSettings !== 'function') return {};
    try { return track.getSettings() || {}; } catch (_) { return {}; }
}

function isMobileMicTarget() {
    if (navigator.userAgentData && typeof navigator.userAgentData.mobile === 'boolean') {
        return navigator.userAgentData.mobile;
    }
    return /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent || '');
}

function isDefaultDeviceAlias(device) {
    return !device || device.deviceId === 'default' || /^default(?:\s|$)/i.test(device.label || '');
}

function isExternalMicLabel(label) {
    return EXTERNAL_MIC_LABEL_PATTERN.test(String(label || ''));
}

function findBuiltInMicDevice(devices) {
    const candidates = (devices || [])
        .filter(device => device.kind === 'audioinput'
            && device.deviceId
            && !isDefaultDeviceAlias(device)
            && BUILTIN_MIC_LABEL_PATTERN.test(device.label || '')
            && !isExternalMicLabel(device.label))
        // 実デバイスIDを優先。同一マイクのdefault別名は固定先に使わない。
        .sort((a, b) => (b.label || '').length - (a.label || '').length);
    return candidates[0] || null;
}

async function enumerateAudioDevices() {
    if (!navigator.mediaDevices || typeof navigator.mediaDevices.enumerateDevices !== 'function') {
        return [];
    }
    try {
        return (await navigator.mediaDevices.enumerateDevices())
            .filter(device => device.kind === 'audioinput' || device.kind === 'audiooutput');
    } catch (error) {
        console.warn('[STT mic enumeration failed]', error);
        return [];
    }
}

function describeSelectedMic(stream, devices) {
    const track = stream && stream.getAudioTracks()[0];
    const settings = readMicSettings(track);
    const matched = (devices || []).find(device =>
        device.kind === 'audioinput' && settings.deviceId && device.deviceId === settings.deviceId);
    const label = (matched && matched.label) || (track && track.label) || '';
    return {
        deviceId: settings.deviceId || '',
        groupId: settings.groupId || '',
        label,
        external: isExternalMicLabel(label),
        builtIn: BUILTIN_MIC_LABEL_PATTERN.test(label) && !isExternalMicLabel(label)
    };
}

function buildMicConstraintAttempts(noiseOn, supported, preferredDevice) {
    const common = {
        // 2ch(複数マイク)端末では両チャンネルを拾い、キャプチャ側でモノラルへダウンミックスする。
        // モノラル端末では ideal のため従来どおりモノラルで取得される。
        channelCount: { ideal: 2 },
        sampleRate: { ideal: 48000 },
        sampleSize: { ideal: 16 }
    };
    if (preferredDevice && preferredDevice.deviceId) {
        // ideal ではOSの既定経路（Bluetooth等）へ戻され得るため、必須指定にする。
        common.deviceId = { exact: preferredDevice.deviceId };
    }
    const exact = {};
    const relaxed = {};
    const desired = noiseOn
        ? { echoCancellation: false, noiseSuppression: true, autoGainControl: true, voiceIsolation: false }
        : { echoCancellation: false, noiseSuppression: false, autoGainControl: false, voiceIsolation: false };

    Object.entries(desired).forEach(([name, value]) => {
        relaxed[name] = value;
        if (supported[name]) exact[name] = { exact: value };
    });

    const attempts = [];
    // 一部の端末は channelCount を ideal 指定だとモノラルで応答するが、
    // exact 指定ならステレオを返せる。ステレオが取れれば両マイクを
    // ダウンミックスして録音できるため、最初に必須指定で試す。
    // 対応しない端末は OverconstrainedError で即フォールバックする。
    if (supported.channelCount) {
        attempts.push({
            label: 'stereo-exact',
            strict: false,
            fullyStrict: false,
            constraints: { ...relaxed, ...common, channelCount: { exact: 2 } }
        });
    }
    // 2024年以降の一部Chrome/Androidは複数の exact:false を拒否する報告がある。
    // さらに、全項目を exact でまとめると、どれか一つが変更不能な端末でセット全体が
    // reject される。そこで OFF は EC 単独、NS 単独、全項目、通常指定の順で試す。
    // Chromium/Android では初回の EC exact:false が APM 全体を止める鍵になる。
    if (!noiseOn && supported.echoCancellation) {
        attempts.push({
            label: 'raw-ec-master',
            strict: true,
            fullyStrict: false,
            constraints: { ...relaxed, echoCancellation: { exact: false }, ...common }
        });
    }
    if (!noiseOn && supported.noiseSuppression) {
        attempts.push({
            label: 'raw-ns-exact',
            strict: true,
            fullyStrict: false,
            constraints: { ...relaxed, noiseSuppression: { exact: false }, ...common }
        });
    }
    if (Object.keys(exact).length) {
        attempts.push({
            label: noiseOn ? 'processed-exact' : 'raw-exact',
            strict: true,
            fullyStrict: ['echoCancellation', 'noiseSuppression', 'autoGainControl'].every(name => !!supported[name]),
            constraints: { ...exact, ...common }
        });
    }
    attempts.push({
        label: noiseOn ? 'processed-relaxed' : 'raw-relaxed',
        strict: false,
        fullyStrict: false,
        constraints: { ...relaxed, ...common }
    });
    return attempts;
}

function summarizeMicState(noiseOn, settings, fullyStrict) {
    const expected = noiseOn
        ? { echoCancellation: false, noiseSuppression: true, autoGainControl: true }
        : { echoCancellation: false, noiseSuppression: false, autoGainControl: false };
    const mismatches = [];
    const unknown = [];
    Object.entries(expected).forEach(([name, value]) => {
        if (typeof settings[name] !== 'boolean') unknown.push(name);
        else if (settings[name] !== value) mismatches.push(name);
    });
    return {
        mismatch: mismatches.length > 0,
        mismatches,
        unknown,
        // exact制約が成功した場合は、getSettingsが省略されてもブラウザ層では保証済み。
        verified: mismatches.length === 0 && (fullyStrict || unknown.length === 0),
        processingFullyOff: !noiseOn && mismatches.length === 0 && (fullyStrict || unknown.length === 0)
    };
}

async function acquireMicStreamCandidate(noiseOn, preferredDevice) {
    const supported = (navigator.mediaDevices.getSupportedConstraints
        ? navigator.mediaDevices.getSupportedConstraints()
        : {}) || {};
    const attempts = buildMicConstraintAttempts(noiseOn, supported, preferredDevice);
    const diagnostics = [];
    let lastError = null;

    for (const attempt of attempts) {
        try {
            const stream = await navigator.mediaDevices.getUserMedia({ audio: attempt.constraints });
            const track = stream.getAudioTracks()[0];
            if (!track) {
                stopStreamTracks(stream);
                throw new Error('マイクの音声トラックを取得できませんでした');
            }
            try { track.enabled = true; } catch (_) {}
            try { track.contentHint = noiseOn ? 'speech' : ''; } catch (_) {}

            const settings = readMicSettings(track);
            const state = summarizeMicState(noiseOn, settings, attempt.fullyStrict);
            let appliedConstraints = {};
            try { appliedConstraints = track.getConstraints ? track.getConstraints() : {}; } catch (_) {}
            diagnostics.push({
                label: attempt.label,
                success: true,
                strict: attempt.strict,
                fullyStrict: attempt.fullyStrict,
                settings,
                mismatches: state.mismatches,
                unknown: state.unknown
            });

            // 一部だけ exact の互換戦略で残りの設定が一致しない／不明なら、
            // ストリームを完全に閉じてから次を取得する。複数の入力を同時に保持すると
            // Androidの MODE_IN_COMMUNICATION が残り、次のraw取得まで処理付きになるため。
            const isLastAttempt = attempt === attempts[attempts.length - 1];
            if (!state.verified && !isLastAttempt) {
                stopStreamTracks(stream);
                await new Promise(resolve => window.setTimeout(resolve, 80));
                continue;
            }

            const micInfo = {
                requestedNoiseOn: noiseOn,
                requestedDeviceId: preferredDevice ? preferredDevice.deviceId : '',
                requestedDeviceLabel: preferredDevice ? preferredDevice.label : '',
                strategy: attempt.label,
                strict: attempt.strict,
                fullyStrict: attempt.fullyStrict,
                echoCancellation: settings.echoCancellation,
                noiseSuppression: settings.noiseSuppression,
                autoGainControl: settings.autoGainControl,
                voiceIsolation: settings.voiceIsolation,
                mismatch: state.mismatch,
                mismatches: state.mismatches,
                unknown: state.unknown,
                verified: state.verified,
                processingFullyOff: state.processingFullyOff,
                appliedConstraints,
                attempts: diagnostics
            };
            stream._sttMicSettings = micInfo;
            stream._sttSupportedConstraints = supported;
            return stream;
        } catch (error) {
            lastError = error;
            diagnostics.push({
                label: attempt.label,
                success: false,
                strict: attempt.strict,
                error: String(error && (error.name || error.message) || error)
            });
        }
    }

    console.warn('[STT mic acquisition failed]', diagnostics);
    throw lastError || new Error('マイクを取得できませんでした');
}

function publishMicDiagnostics(stream) {
    const track = stream && stream.getAudioTracks()[0];
    const settings = readMicSettings(track);
    const micInfo = (stream && stream._sttMicSettings) || {};
    window.__sttLastMicDiagnostics = {
        ...micInfo,
        sampleRate: settings.sampleRate,
        channelCount: settings.channelCount,
        deviceId: settings.deviceId,
        groupId: settings.groupId,
        supportedConstraints: (stream && stream._sttSupportedConstraints) || {}
    };
    console.log('[STT mic diagnostics]', window.__sttLastMicDiagnostics);
}

/**
 * モバイルではChromeが公開した「内蔵マイク」を実デバイスIDで取り直す。
 * 初回許可前はラベルが空なので、一度だけ処理条件を確定したストリームを取得し、
 * ラベル公開後に停止してから内蔵マイクを exact 指定する。
 */
async function acquireMicStream(noiseOn) {
    const mobileTarget = isMobileMicTarget();
    let devices = mobileTarget ? await enumerateAudioDevices() : [];
    let builtInDevice = mobileTarget ? findBuiltInMicDevice(devices) : null;
    let stream = null;
    let routingFailure = '';

    if (builtInDevice) {
        try {
            stream = await acquireMicStreamCandidate(noiseOn, builtInDevice);
        } catch (error) {
            routingFailure = String(error && (error.name || error.message) || error);
            console.warn('[STT built-in mic exact selection failed]', error);
        }
    }

    if (!stream) {
        stream = await acquireMicStreamCandidate(noiseOn, null);
    }

    // 権限取得後に初めて実デバイス名が見える場合は、probeを完全停止して取り直す。
    if (mobileTarget && !builtInDevice) {
        devices = await enumerateAudioDevices();
        builtInDevice = findBuiltInMicDevice(devices);
        if (builtInDevice) {
            stopStreamTracks(stream);
            stream = null;
            await new Promise(resolve => window.setTimeout(resolve, 120));
            try {
                stream = await acquireMicStreamCandidate(noiseOn, builtInDevice);
            } catch (error) {
                routingFailure = String(error && (error.name || error.message) || error);
                console.warn('[STT built-in mic reacquisition failed]', error);
                // 内蔵の厳密指定を端末が拒否した場合も、録音自体は可能にする。
                stream = await acquireMicStreamCandidate(noiseOn, null);
            }
        }
    }

    devices = mobileTarget ? await enumerateAudioDevices() : devices;
    const selected = describeSelectedMic(stream, devices);
    const micInfo = stream._sttMicSettings || {};
    const exactRequested = !!builtInDevice && micInfo.requestedDeviceId === builtInDevice.deviceId;
    const deviceIdReported = !!selected.deviceId;
    const exactVerified = exactRequested && deviceIdReported
        && selected.deviceId === builtInDevice.deviceId;
    const exactMismatch = exactRequested && deviceIdReported && !exactVerified;
    let routingMode = 'not-mobile';
    let routingWarning = '';

    if (mobileTarget && exactVerified) {
        routingMode = 'built-in-exact-verified';
    } else if (mobileTarget && exactRequested && !deviceIdReported) {
        // getUserMediaのexact成功によりブラウザ層では固定済みだが、
        // getSettingsがIDを省略する端末では実測照合まではできない。
        routingMode = 'built-in-exact-requested';
    } else if (mobileTarget && exactMismatch) {
        routingMode = 'built-in-exact-mismatch';
        routingWarning = 'Chromeが内蔵マイクの固定指定と異なる入力を返しました';
    } else if (mobileTarget && routingFailure) {
        routingMode = 'built-in-exact-failed';
        routingWarning = selected.external
            ? '内蔵マイクの固定に失敗し、外部マイクが選択されています。イヤフォン等を切断してください'
            : '内蔵マイクの固定を端末が拒否したため、端末の既定入力を使用しています';
    } else if (mobileTarget) {
        routingMode = 'built-in-not-identifiable';
        routingWarning = selected.external
            ? 'イヤフォン等の外部マイクが選択されています。切断してから録音し直してください'
            : 'Chromeが内蔵マイクを個別公開しないため、端末の既定入力を使用しています';
    }

    micInfo.routing = {
        mobileTarget,
        mode: routingMode,
        exactRequested,
        exactVerified,
        requestedLabel: builtInDevice ? builtInDevice.label : '',
        selectedLabel: selected.label,
        selectedExternal: selected.external,
        selectedBuiltIn: selected.builtIn,
        warning: routingWarning,
        failure: routingFailure
    };
    stream._sttMicSettings = micInfo;
    publishMicDiagnostics(stream);
    return stream;
}

function assertMicProcessingVerified(noiseOn, stream) {
    const mic = (stream && stream._sttMicSettings) || {};
    if (mic.verified) return mic;
    const fieldNames = {
        echoCancellation: 'EC',
        noiseSuppression: 'NS',
        autoGainControl: 'AGC'
    };
    const failedFields = [...(mic.mismatches || []), ...(mic.unknown || [])]
        .map(name => fieldNames[name] || name)
        .join('/');
    const error = new Error(
        `ノイズ除去${noiseOn ? 'ON' : 'OFF'}を確認できないため録音を開始しません`
        + (failedFields ? `（確認項目: ${failedFields}）` : '')
    );
    error.isMicProcessingVerificationError = true;
    throw error;
}
