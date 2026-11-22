package com.example.xiaomiao

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.BufferedInputStream
import kotlin.math.min

/**
 * 播放唤醒后的接入提示音与问候语（本地 assets wav，48kHz mono PCM16）。
 * 官网 greeting 音频幅度很低（peak≈1%），这里用较大增益放大。
 */
class GreetingPlayer(private val context: Context) {
    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val ASSET = "greeting/female.wav"
        private const val GAIN = 24.0f
        // 挂断音已在生成时归一化到峰值 0.9，无需额外增益。
        private const val FAREWELL_ASSET = "greeting/farewell.wav"
    }

    /** 独立的挂断音播放器，避免与问候音共用 track 导致抢占。 */
    private var farewellTrack: AudioTrack? = null

    private var track: AudioTrack? = null

    /** 播放问候语，返回实际播放时长（毫秒）；失败返回 0。 */
    fun play(): Long = playAsset(ASSET, GAIN)

    /** 播放挂断提示音「对话已结束」，返回时长；失败返回 0。 */
    fun playFarewell(): Long = playAsset(FAREWELL_ASSET, 1.0f, useFarewellTrack = true)

    private fun playAsset(asset: String, gain: Float, useFarewellTrack: Boolean = false): Long {
        val pcm = readWavPcm(asset) ?: return 0L
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // 与 AI 播放流统一到通话音量流，避免会话中音量忽大忽小。
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, pcm.size))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        val boosted = applyGain(pcm, gain)
        track.write(boosted, 0, boosted.size)
        track.setVolume(1.0f)
        track.play()
        if (useFarewellTrack) this.farewellTrack = track else this.track = track
        return (pcm.size / 2L) * 1000L / SAMPLE_RATE
    }

    fun stop() {
        track?.runCatching { stop(); release() }
        track = null
    }

    /** 只停挂断音（会话结束时问候音早已播完，不必混在一起）。 */
    fun stopFarewell() {
        farewellTrack?.runCatching { stop(); release() }
        farewellTrack = null
    }

    /** 读取 assets 中的 wav，跳过 RIFF 头，返回 PCM16 数据。 */
    private fun readWavPcm(asset: String): ByteArray? = try {
        BufferedInputStream(context.assets.open(asset)).use { input ->
            val all = input.readBytes()
            // 定位 "data" 子块，取其后的 4 字节长度 + PCM 数据。
            var dataPos = -1
            var i = 12
            while (i + 8 <= all.size) {
                val id = String(all, i, 4, Charsets.US_ASCII)
                if (id == "data") {
                    dataPos = i + 8
                    break
                }
                val size = (all[i + 4].toInt() and 0xFF) or
                    ((all[i + 5].toInt() and 0xFF) shl 8) or
                    ((all[i + 6].toInt() and 0xFF) shl 16) or
                    ((all[i + 7].toInt() and 0xFF) shl 24)
                i += 8 + size + (size and 1)
            }
            if (dataPos < 0 || dataPos > all.size) null else all.copyOfRange(dataPos, all.size)
        }
    } catch (_: Exception) {
        null
    }

    private fun applyGain(bytes: ByteArray, gain: Float): ByteArray {
        val out = ByteArray(bytes.size)
        var i = 0
        while (i + 1 < bytes.size) {
            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort()
            val boosted = min(32767f, sample.toFloat() * gain).toInt().toShort()
            out[i] = (boosted.toInt() and 0xFF).toByte()
            out[i + 1] = ((boosted.toInt() shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }
}
