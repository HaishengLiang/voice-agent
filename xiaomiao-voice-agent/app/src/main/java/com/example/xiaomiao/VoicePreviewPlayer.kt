package com.example.xiaomiao

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 音色试听播放器：下载 MOSS 音色库提供的签名 wav（preview_audio_url），
 * 解析 RIFF 头后按 wav 原始声道数用 AudioTrack 播放（不做降混，保留立体声）。
 * 不走 TTS 生成，不消耗积分。
 *
 * 签名 URL 有时效（约 3 天），列表刷新后 URL 会更新，故每次试听都重新下载。
 */
class VoicePreviewPlayer {
    private val http = OkHttpClient.Builder()
        .callTimeout(25, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var currentTrack: AudioTrack? = null

    /** 停止当前试听。切换音色或离开页面时调用。 */
    fun stop() {
        currentTrack?.runCatching { stop(); release() }
        currentTrack = null
    }

    /**
     * 下载并播放一个试听 URL。返回实际播放时长（毫秒）；下载/解析失败抛异常。
     * IO 在后台线程，播放在主线程调用（AudioTrack 创建/写入在主线程即可）。
     */
    suspend fun play(url: String): Long = withContext(Dispatchers.IO) {
        if (url.isBlank()) throw IOException("该音色暂无试听音频")
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        val bytes = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("试听音频下载失败（HTTP ${response.code}）")
            response.body?.bytes() ?: throw IOException("试听音频为空")
        }
        val pcm = decodeWav(bytes) ?: throw IOException("试听音频格式异常")
        val channelMask = if (pcm.channels >= 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(pcm.sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(
                pcm.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT,
            ), pcm.data.size))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        // 切到主线程操作 AudioTrack，避免与上一次播放的 stop 竞争
        withContext(Dispatchers.Main) {
            stop()
            track.write(pcm.data, 0, pcm.data.size)
            track.play()
            currentTrack = track
        }
        // 每帧字节数 = 声道数 * 2（PCM16）
        (pcm.data.size / (pcm.channels * 2L)) * 1000L / pcm.sampleRate
    }

    data class Pcm(val data: ByteArray, val sampleRate: Int, val channels: Int)

    /** 解析 wav：fmt 取采样率/声道数，data 原样取 PCM16（保留原始声道，不降混）。 */
    private fun decodeWav(all: ByteArray): Pcm? {
        if (all.size < 44) return null
        var dataPos = -1
        var dataLen = 0
        var sampleRate = 48_000
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
                    if (i + 24 <= all.size) {
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
        return Pcm(all.copyOfRange(dataPos, dataPos + dataLen), sampleRate, channels)
    }
}
