package com.minashin1120.voxcribe.audio

import de.sciss.jump3r.mp3.BRHist
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class FinalizeResult(val file: File, val gain: Double, val peakBefore: Double)

/**
 * 録音停止後の処理。
 * 1. ステレオ（2マイク）録音なら (L+R)×0.5 でモノラル統合
 * 2. Web版 encodeNormalizedRecording と同じ定数でレベル補正
 * 3. WAV(16bit mono) または MP3(192kbps mono) へストリーミング書き出し
 */
object Finalize {

    fun run(raw: File, channels: Int, sampleRate: Int, format: String, agcOff: Boolean, noiseOn: Boolean, out: File): FinalizeResult {
        // ---- パス1: レベル計測（measureRecordingLevels）----
        var peak = 0.0
        val frameLength = max(128, (sampleRate * 0.02).roundToInt())
        val frameRms = FloatList()
        var frameSum = 0.0
        var frameSamples = 0
        var totalSamples = 0L
        forEachMono(raw, channels) { s ->
            val a = abs(s.toDouble())
            if (a > peak) peak = a
            frameSum += s.toDouble() * s
            frameSamples++
            totalSamples++
            if (frameSamples == frameLength) {
                frameRms.add(sqrt(frameSum / frameSamples).toFloat())
                frameSum = 0.0
                frameSamples = 0
            }
        }
        if (frameSamples > 0) frameRms.add(sqrt(frameSum / frameSamples).toFloat())
        if (totalSamples == 0L) throw IllegalStateException("録音データがありません")
        val sorted = frameRms.toArray().also { it.sort() }
        val noiseRms = percentile(sorted, 0.2)
        val threshold = max(0.0008, noiseRms * 1.8)
        val speech = sorted.filter { it >= threshold }.toFloatArray()
        val speechRms = if (speech.size >= 3) percentile(speech, 0.65) else percentile(sorted, 0.9)

        // ---- computeMakeupGain ----
        val gain = if (!(speechRms > 1e-6)) 1.0 else {
            val target = 0.09
            val maxGain = if (noiseOn) 6.0 else if (agcOff) 12.0 else 8.0
            min(maxGain, max(1.0, target / speechRms))
        }

        // ---- パス2: softLimit → int16 → エンコード ----
        val block = ShortArray(4096)
        var n = 0
        if (format == "wav") {
            RandomAccessFile(out, "rw").use { it.setLength(0) }
            BufferedOutputStream(FileOutputStream(out), 256 * 1024).use { os ->
                writeWavHeader(os, sampleRate, totalSamples)
                forEachMono(raw, channels) { s ->
                    val v = toInt16(softLimit(s * gain))
                    os.write(v.toInt() and 0xff)
                    os.write((v.toInt() shr 8) and 0xff)
                }
            }
        } else {
            val enc = Mp3Encoder(sampleRate)
            BufferedOutputStream(FileOutputStream(out), 256 * 1024).use { os ->
                forEachMono(raw, channels) { s ->
                    block[n++] = toInt16(softLimit(s * gain))
                    if (n == block.size) {
                        enc.encode(block, n, os)
                        n = 0
                    }
                }
                if (n > 0) enc.encode(block, n, os)
                enc.finish(os)
            }
        }
        raw.delete()
        return FinalizeResult(out, gain, peak)
    }

    private inline fun forEachMono(raw: File, channels: Int, fn: (Float) -> Unit) {
        DataInputStream(BufferedInputStream(FileInputStream(raw), 256 * 1024)).use { input ->
            val bytes = ByteArray(4 * 4096 * channels)
            while (true) {
                val read = readFully(input, bytes)
                if (read <= 0) break
                val floats = read / 4
                var i = 0
                if (channels == 2) {
                    while (i + 1 < floats) {
                        val l = leFloat(bytes, i * 4)
                        val r = leFloat(bytes, (i + 1) * 4)
                        // 2マイクの信号を均等にモノラル統合
                        fn((l + r) * 0.5f)
                        i += 2
                    }
                } else {
                    while (i < floats) {
                        fn(leFloat(bytes, i * 4))
                        i++
                    }
                }
                if (read < bytes.size) break
            }
        }
    }

    private fun readFully(input: DataInputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = try { input.read(buf, off, buf.size - off) } catch (_: EOFException) { -1 }
            if (r < 0) break
            off += r
        }
        return off
    }

    private fun leFloat(b: ByteArray, o: Int): Float = java.lang.Float.intBitsToFloat(
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
    )

    private fun percentile(sorted: FloatArray, ratio: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val idx = min(sorted.size - 1, floor(sorted.size * ratio).toInt())
        return sorted[idx].toDouble()
    }

    /** Web版 softLimit と同一 */
    fun softLimit(sample: Double): Double {
        val sign = if (sample < 0) -1.0 else 1.0
        val v = abs(sample)
        if (v <= 0.82) return sample
        return sign * (0.82 + 0.18 * (1 - exp(-(v - 0.82) / 0.18)))
    }

    private fun toInt16(x: Double): Short {
        val s = x.coerceIn(-1.0, 1.0)
        return (if (s < 0) s * 0x8000 else s * 0x7FFF).toInt().toShort()
    }

    private fun writeWavHeader(os: OutputStream, sampleRate: Int, samples: Long) {
        val dataSize = (samples * 2).toInt()
        fun str(s: String) = os.write(s.toByteArray(Charsets.US_ASCII))
        fun u32(v: Int) { os.write(v and 0xff); os.write((v shr 8) and 0xff); os.write((v shr 16) and 0xff); os.write((v shr 24) and 0xff) }
        fun u16(v: Int) { os.write(v and 0xff); os.write((v shr 8) and 0xff) }
        str("RIFF"); u32(36 + dataSize); str("WAVE"); str("fmt ")
        u32(16); u16(1); u16(1); u32(sampleRate); u32(sampleRate * 2); u16(2); u16(16)
        str("data"); u32(dataSize)
    }

    private class FloatList {
        private var data = FloatArray(1 shl 14)
        private var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        fun toArray() = data.copyOf(size)
    }
}

/**
 * LAME（jump3r 純Java移植）による MP3 192kbps モノラルエンコーダ。
 * jump3r の lowlevel.LameEncoder は javax.sound に依存するため、同等の初期化を直接行う。
 */
class Mp3Encoder(sampleRate: Int) {
    private val lame = Lame()
    private val gfp: LameGlobalFlags
    private val mp3buf = ByteArray(1024 * 1024)

    init {
        val gaud = GetAudio()
        val ga = GainAnalysis()
        val bs = BitStream()
        val p = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val vbr = VBRTag()
        val ver = Version()
        val id3 = ID3Tag()
        val rv = Reservoir()
        val tak = Takehiro()
        val parse = Parse()
        @Suppress("UNUSED_VARIABLE") val hist = BRHist()
        val mpg = MPGLib()
        val intf = Interface()
        val common = Common()

        lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        id3.setModules(bs, ver)
        p.setModules(lame)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lame.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbr.setModules(lame, bs, ver)
        gaud.setModules(parse, mpg)
        parse.setModules(ver, id3, p)
        mpg.setModules(intf, common)
        intf.setModules(vbr, common)

        gfp = lame.lame_init()
        gfp.num_channels = 1
        gfp.in_samplerate = sampleRate
        gfp.out_samplerate = sampleRate
        gfp.mode = MPEGMode.MONO
        gfp.brate = 192
        gfp.quality = 3
        gfp.bWriteVbrTag = false
        id3.id3tag_init(gfp)
        gfp.write_id3tag_automatic = false
        gfp.findReplayGain = false
        val rc = lame.lame_init_params(gfp)
        require(rc >= 0) { "MP3エンコーダの初期化に失敗しました ($rc)" }
    }

    fun encode(pcm: ShortArray, n: Int, os: OutputStream) {
        val l = IntArray(n)
        for (i in 0 until n) l[i] = pcm[i].toInt() shl 16
        val bytes = lame.lame_encode_buffer_int(gfp, l, l, n, mp3buf, 0, mp3buf.size)
        if (bytes > 0) os.write(mp3buf, 0, bytes)
    }

    fun finish(os: OutputStream) {
        val bytes = lame.lame_encode_flush(gfp, mp3buf, 0, mp3buf.size)
        if (bytes > 0) os.write(mp3buf, 0, bytes)
        lame.lame_close(gfp)
    }
}
