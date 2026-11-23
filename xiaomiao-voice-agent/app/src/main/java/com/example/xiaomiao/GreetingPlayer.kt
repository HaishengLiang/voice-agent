package com.example.xiaomiao

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.BufferedInputStream
import kotlin.math.min

/**
 * 播放唤醒后的接入提示音（联通音）与挂断提示音。
 *
 * 音源优先级：
 * 1. API Key 模式下用户用 MOSS TTS 生成、保存在 filesDir 的 wav；
 * 2. 否则回退到 assets 里自带的 wav（demo 模式固定走这一路）。
 *
 * assets 自带音与 TTS 生成音都是 MOSS TTS 产物：48 kHz / 16 bit，前者单声道、
 * 后者可能立体声。故从 RIFF 头解析采样率与声道数，立体声先降混为单声道，
 * 再用实际采样率创建 AudioTrack，避免音调/时长错乱。
 */
class GreetingPlayer(private val context: Context) {
    data class Pcm(val data: ByteArray, val sampleRate: Int)

    companion object {
        private const val DEFAULT_SAMPLE_RATE = 48_000
        // assets 自带提示音已用 MOSS TTS 生成并归一化（peak≈90%），与运行时 TTS 本地文件一样用 1.0 增益。
        private const val ASSET_GAIN = 1.0f
        private const val TTS_GAIN = 1.0f
        private const val ASSET_GREETING = "greeting/female.wav"
        private const val ASSET_FAREWELL = "greeting/farewell.wav"
    }

    /** 独立的挂断音播放器，避免与问候音共用 track 导致抢占。 */
    private var farewellTrack: AudioTrack? = null
    private var track: AudioTrack? = null

    /** 是否使用 TTS 本地文件（非 demo 且文件已生成）。 */
    private val useLocalGreeting: Boolean
        get() = !Config.useDemo && Config.hasToneFile(context, Config.GREETING_FILE)
    private val useLocalFarewell: Boolean
        get() = !Config.useDemo && Config.hasToneFile(context, Config.FAREWELL_FILE)

    /** 播放联通提示音，返回实际播放时长（毫秒）；失败返回 0。 */
    fun play(): Long {
        val (pcm, gain) = if (useLocalGreeting) {
            readWavFile(Config.GREETING_FILE) to TTS_GAIN
        } else {
            readWavAsset(ASSET_GREETING) to ASSET_GAIN
        }
        return playPcm(pcm, gain, useFarewellTrack = false)
    }

    /** 播放挂断提示音，返回时长；失败返回 0。 */
    fun playFarewell(): Long {
        val (pcm, gain) = if (useLocalFarewell) {
            readWavFile(Config.FAREWELL_FILE) to TTS_GAIN
        } else {
            readWavAsset(ASSET_FAREWELL) to 1.0f
        }
        return playPcm(pcm, gain, useFarewellTrack = true)
    }

    private fun playPcm(pcm: Pcm?, gain: Float, useFarewellTrack: Boolean): Long {
        if (pcm == null || pcm.data.isEmpty()) return 0L
        val boosted = applyGain(pcm.data, gain)
        val track = buildTrack(pcm.sampleRate, boosted.size)
        track.write(boosted, 0, boosted.size)
        track.setVolume(1.0f)
        track.play()
        if (useFarewellTrack) this.farewellTrack = track else this.track = track
        return (pcm.data.size / 2L) * 1000L / pcm.sampleRate
    }

    private fun buildTrack(sampleRate: Int, bufferSize: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
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
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, bufferSize))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
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

    private fun readWavFile(name: String): Pcm? = try {
        context.openFileInput(name).use { parseWav(it.readBytes()) }
    } catch (_: Exception) {
        null
    }

    private fun readWavAsset(asset: String): Pcm? = try {
        BufferedInputStream(context.assets.open(asset)).use { parseWav(it.readBytes()) }
    } catch (_: Exception) {
        null
    }

    /** 解析 wav：定位 fmt 块取采样率/声道数，定位 data 块取 PCM16 数据；立体声降混为单声道。 */
    private fun parseWav(all: ByteArray): Pcm? {
        if (all.size < 44) return null
        var dataPos = -1
        var dataLen = 0
        var sampleRate = DEFAULT_SAMPLE_RATE
        var channels = 1
        var i = 12
        while (i + 8 <= all.size) {
            val id = String(all, i, 4, Charsets.US_ASCII)
            val size = (all[i + 4].toInt() and 0xFF) or
                ((all[i + 5].toInt() and 0xFF) shl 8) or
                ((all[i + 6].toInt() and 0xFF) shl 16) or
                ((all[i + 7].toInt() and 0xFF) shl 24)
            when (id) {
                "fmt " -> {
                    if (i + 8 + 16 <= all.size) {
                        channels = (all[i + 10].toInt() and 0xFF) or
                            ((all[i + 11].toInt() and 0xFF) shl 8)
                        sampleRate = (all[i + 12].toInt() and 0xFF) or
                            ((all[i + 13].toInt() and 0xFF) shl 8) or
                            ((all[i + 14].toInt() and 0xFF) shl 16) or
                            ((all[i + 15].toInt() and 0xFF) shl 24)
                    }
                }
                "data" -> {
                    dataPos = i + 8
                    dataLen = size
                    break
                }
            }
            i += 8 + size + (size and 1)
        }
        if (dataPos < 0 || dataPos + dataLen > all.size) return null
        val raw = all.copyOfRange(dataPos, dataPos + dataLen)
        // MOSS TTS 运行时生成的是 48kHz 立体声 wav；AudioTrack 按单声道播放，需先降混，否则变调/时长翻倍。
        val mono = if (channels >= 2) downmixToMono(raw, channels) else raw
        return Pcm(mono, sampleRate)
    }

    /** PCM16 多声道取各声道平均值降为单声道。 */
    private fun downmixToMono(raw: ByteArray, channels: Int): ByteArray {
        val bytesPerSample = 2
        val frameBytes = channels * bytesPerSample
        val frames = raw.size / frameBytes
        val out = ByteArray(frames * bytesPerSample)
        var f = 0
        while (f < frames) {
            var sum = 0
            var c = 0
            while (c < channels) {
                val p = f * frameBytes + c * bytesPerSample
                if (p + 1 < raw.size) {
                    sum += ((raw[p + 1].toInt() shl 8) or (raw[p].toInt() and 0xFF)).toShort().toInt()
                }
                c++
            }
            val avg = (sum / channels).coerceIn(-32768, 32767).toShort()
            out[f * 2] = (avg.toInt() and 0xFF).toByte()
            out[f * 2 + 1] = ((avg.toInt() shr 8) and 0xFF).toByte()
            f++
        }
        return out
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
