package com.minashin1120.voxcribe.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 録音開始時に検証した実効設定（Web版 readMicSettings 相当） */
data class MicSettings(
    val channels: Int,
    val requestedStereo: Boolean,
    val sampleRate: Int,
    val sourceLabel: String,
    val noiseSuppression: Boolean?,
    val autoGainControl: Boolean?,
    val echoCancellation: Boolean?,
    val processingFullyOff: Boolean,
    val deviceLabel: String,
    val builtInCount: Int,
)

class MicProcessingException(message: String) : Exception(message)

/**
 * AudioRecord による録音。
 * - 内蔵マイクが2つ以上ならステレオ（CHANNEL_IN_STEREO）で両マイクからキャプチャ。
 * - 録音中はインターリーブ float をキャッシュファイルへ書き出し（長時間でもメモリを使わない）。
 * - 停止後に Finalize でモノラル統合 → 正規化 → エンコードする。
 */
class NativeRecorder(private val cacheDir: File) {
    @Volatile var paused = false
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var out: DataOutputStream? = null
    private val effects = mutableListOf<android.media.audiofx.AudioEffect>()

    var rawFile: File? = null
        private set
    var settings: MicSettings? = null
        private set

    /** ビジュアライザー用: 直近ブロックのモノラルサンプル */
    @Volatile var onBlock: ((FloatArray) -> Unit)? = null

    /**
     * マイクを初期化し、ノイズ除去スイッチと実効設定が一致するか検証する。
     * 一致しない場合は MicProcessingException を投げる（Web版 assertMicProcessingVerified 相当）。
     */
    @SuppressLint("MissingPermission")
    fun open(mic: MicInfo, noiseOn: Boolean): MicSettings {
        release()
        val rate = 48000
        val attempts = buildList {
            if (mic.stereo) add(AudioFormat.CHANNEL_IN_STEREO)
            add(AudioFormat.CHANNEL_IN_MONO)
        }
        val source = when {
            noiseOn -> MediaRecorder.AudioSource.MIC
            mic.unprocessedSupported -> MediaRecorder.AudioSource.UNPROCESSED
            else -> MediaRecorder.AudioSource.MIC
        }
        var rec: AudioRecord? = null
        var usedMask = AudioFormat.CHANNEL_IN_MONO
        for (mask in attempts) {
            val minBuf = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_FLOAT)
            if (minBuf <= 0) continue
            val r = try {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf * 4, rate * 4 * 2 / 5))
                    .build()
            } catch (_: Exception) {
                null
            }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                rec = r
                usedMask = mask
                break
            }
            r?.release()
        }
        if (rec == null) throw MicProcessingException("マイクを初期化できませんでした")
        mic.device?.let { rec.setPreferredDevice(it) }

        // エフェクト: ON=NS+AGC、OFF=すべて無効
        val sid = rec.audioSessionId
        var ns: Boolean? = null
        var agc: Boolean? = null
        var ec: Boolean? = null
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(sid)?.let { it.setEnabled(noiseOn); ns = it.getEnabled(); effects += it }
        } else if (!noiseOn) ns = false
        if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(sid)?.let { it.setEnabled(noiseOn); agc = it.getEnabled(); effects += it }
        } else if (!noiseOn) agc = false
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(sid)?.let { it.setEnabled(false); ec = it.getEnabled(); effects += it }
        } else ec = false

        val failing = mutableListOf<String>()
        if (ec == true) failing += "EC"
        if (noiseOn) {
            if (ns == false) failing += "NS"
        } else {
            if (ns == true) failing += "NS"
            if (agc == true) failing += "AGC"
        }
        if (failing.isNotEmpty()) {
            rec.release()
            releaseEffects()
            throw MicProcessingException(
                "ノイズ除去${if (noiseOn) "ON" else "OFF"}を確認できないため録音を開始しません（確認項目: ${failing.joinToString("/")}）"
            )
        }

        record = rec
        val s = MicSettings(
            channels = if (usedMask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1,
            requestedStereo = mic.stereo,
            sampleRate = rec.sampleRate,
            sourceLabel = if (source == MediaRecorder.AudioSource.UNPROCESSED) "UNPROCESSED" else "MIC",
            noiseSuppression = ns,
            autoGainControl = agc,
            echoCancellation = ec,
            processingFullyOff = !noiseOn && ns != true && agc != true && ec != true,
            deviceLabel = mic.deviceLabel,
            builtInCount = mic.builtInCount,
        )
        settings = s
        return s
    }

    fun isOpen() = record != null

    fun start() {
        val rec = record ?: throw IllegalStateException("not opened")
        val ch = settings?.channels ?: 1
        val f = File(cacheDir, "rec_${System.currentTimeMillis()}.f32")
        rawFile = f
        out = DataOutputStream(BufferedOutputStream(FileOutputStream(f), 256 * 1024))
        paused = false
        running = true
        rec.startRecording()
        thread = Thread({
            val frames = 4096
            val buf = FloatArray(frames * ch)
            val bb = ByteBuffer.allocate(buf.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            val mono = FloatArray(frames)
            while (running) {
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                if (paused) continue
                bb.clear()
                for (i in 0 until n) bb.putFloat(buf[i])
                try {
                    out?.write(bb.array(), 0, n * 4)
                } catch (_: Exception) {
                }
                val nf = n / ch
                if (ch == 2) {
                    for (i in 0 until nf) mono[i] = (buf[2 * i] + buf[2 * i + 1]) * 0.5f
                } else {
                    System.arraycopy(buf, 0, mono, 0, nf)
                }
                onBlock?.invoke(mono.copyOf(nf))
            }
        }, "voxcribe-recorder").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** 停止してキャッシュファイル（インターリーブ float）を返す */
    fun stop(): File? {
        running = false
        try { record?.stop() } catch (_: Exception) {}
        thread?.join(2000)
        thread = null
        try { out?.flush(); out?.close() } catch (_: Exception) {}
        out = null
        release()
        return rawFile
    }

    fun cancel() {
        stop()
        rawFile?.delete()
        rawFile = null
    }

    fun release() {
        releaseEffects()
        try { record?.release() } catch (_: Exception) {}
        record = null
    }

    private fun releaseEffects() {
        effects.forEach { try { it.release() } catch (_: Exception) {} }
        effects.clear()
    }
}
