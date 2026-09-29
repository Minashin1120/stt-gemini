// 録音・アップロード・Grok Live などの画面全体で共有する状態変数。

let selectedFile = null;
let mediaRecorder;
let graphKeepAliveRecorder;
let audioContext;
let processor;
let microphone;
let inputGain;
let silentGain;
let analyserNode;
let captureSink;
let audioStream;
let isRecording = false;
let isPaused = false;
let isAppendMode = false;
let initText = "";
let audioChunks = [];
let floatChunks = [];
let recordFormat = 'mp3';
let recordNoiseSuppression = true;
let abortController = null;
let currentTaskId = null;
let cancellationInProgress = false;
// Grok Live (リアルタイム文字起こし) の状態
let grokLiveWs = null;
let grokLiveText = '';
let grokLiveInterim = '';
let grokLiveFailed = false;
let grokLiveChannels = 1;
let visPeakEma = 0.02;
let capturePeak = 0;
let lastMicSettings = null;
let captureBackend = null;
let captureFlushResolve = null;
let lastLevelUiUpdate = 0;
let preparedNoiseOn = null;
let captureWorkletContext = null;
