// DOMContentLoaded で各モジュールの初期化（イベント登録・初回ロード）を、元の実行順どおりに呼ぶ。
// 読み込み順は templates/index.html の <script> 並びを参照。
document.addEventListener('DOMContentLoaded', () => {
    initUiMic();
    initTransfer();
    initSettings();
    initHistory();
    initStream();
    initRecording();
    initActions();
    initBatch();
});
