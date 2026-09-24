package com.minashin1120.voxcribe.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

data class MicInfo(
    /** 端末内蔵マイクの数 */
    val builtInCount: Int,
    /** ステレオ（2マイク同時）で録音するか */
    val stereo: Boolean,
    /** 選択する内蔵マイクデバイス（無ければ端末既定） */
    val device: AudioDeviceInfo?,
    val deviceLabel: String,
    val unprocessedSupported: Boolean,
    val externalConnected: Boolean,
)

/**
 * Android版専用: 端末の内蔵マイク数を検出する。
 * 2つ以上ならステレオ（L/R それぞれ別マイク）でキャプチャし、停止時にモノラル統合する。
 */
object MicProbe {
    fun probe(context: Context): MicInfo {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        val builtIn = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val external = inputs.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }

        var count = 0
        try {
            count = am.microphones.count { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        } catch (_: Exception) {
        }
        if (count == 0) {
            // getMicrophones が取れない端末: 内蔵マイクデバイスが公開するチャンネル数で代替判定
            val maxCh = inputs.filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                .flatMap { it.channelCounts.toList() }.maxOrNull() ?: 0
            count = when {
                maxCh >= 2 -> 2
                builtIn != null -> 1
                else -> 0
            }
        }

        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val label = builtIn?.productName?.toString()?.takeIf { it.isNotBlank() } ?: "内蔵マイク"
        return MicInfo(
            builtInCount = count,
            stereo = count >= 2,
            device = builtIn,
            deviceLabel = label,
            unprocessedSupported = unprocessed,
            externalConnected = external,
        )
    }
}
