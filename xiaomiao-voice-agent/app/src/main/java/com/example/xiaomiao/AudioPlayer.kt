package com.example.xiaomiao

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import kotlin.math.min

/** 播放 MOSS-Live 返回的 48 kHz mono PCM16 音频块，并做软件增益放大。 */
class AudioPlayer(
    private val handler: Handler,
    private val audioSessionId: Int,
    private val onChunkCompleted: (MossRealtimeClient.AudioMeta) -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 48_000
        const val GAIN = 4.0f
    }

    private var track: AudioTrack? = null

    fun start() {
        if (track != null) return
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE / 5 * 2)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // 关键：VOICE_COMMUNICATION 让本播放流成为系统 AEC 的参考信号。
                    // 若用 USAGE_MEDIA，AEC 拿不到参考 → 回声无法消除 → 打断失效。
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
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
        // 与采集共享 audioSession，进一步确保 AEC 能把播放作为参考。
        if (audioSessionId > 0) builder.setSessionId(audioSessionId)
        val audioTrack = builder.build()
        // 音量走通话流，通话音量由 WakeService 按系统媒体音量比例锚定（不拉满）；
        // 这里保持满轨，响度完全跟随系统通话音量。
        audioTrack.setVolume(1.0f)
        audioTrack.play()
        track = audioTrack
    }

    /** 对 PCM16 数据应用增益（带削波保护），返回新数组。 */
    private fun applyGain(bytes: ByteArray): ByteArray {
        if (GAIN <= 1.0f || bytes.isEmpty()) return bytes
        val out = ByteArray(bytes.size)
        var i = 0
        while (i + 1 < bytes.size) {
            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort()
            val boosted = min(32767f, sample.toFloat() * GAIN).toInt().toShort()
            out[i] = (boosted.toInt() and 0xFF).toByte()
            out[i + 1] = ((boosted.toInt() shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    fun play(bytes: ByteArray, meta: MossRealtimeClient.AudioMeta) {
        val audioTrack = track ?: return
        if (bytes.isEmpty()) return
        val boosted = applyGain(bytes)
        audioTrack.write(boosted, 0, boosted.size)
        val durationMs = (bytes.size / 2L) * 1000L / SAMPLE_RATE
        handler.postDelayed({ onChunkCompleted(meta) }, durationMs.coerceAtLeast(10L))
    }

    fun playedAudioMs(): Long =
        ((track?.playbackHeadPosition ?: 0).toLong() * 1000L / SAMPLE_RATE)

    fun stopAndClear() {
        track?.runCatching {
            pause()
            flush()
        }
    }

    fun release() {
        track?.runCatching {
            stop()
            release()
        }
        track = null
    }
}
