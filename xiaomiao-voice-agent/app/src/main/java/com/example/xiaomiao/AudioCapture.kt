package com.example.xiaomiao

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler

/**
 * 16 kHz / mono / PCM16 麦克风采集。回调运行在采集线程，调用方不要做耗时操作。
 *
 * 关键：必须用 VOICE_COMMUNICATION 音频源，系统才会在驱动层启用 AEC（回声消除）。
 * 用 MIC 源完全没有回声消除——AI 播放的声音会被麦克风采回当成用户输入，
 * 服务端 VAD 就永远认为"用户在说话"，导致打断（barge-in）失效。
 */
class AudioCapture(
    private val sampleRate: Int = 16_000,
    private val frameSamples: Int = 640, // 40 ms
    /** 回调第二参数是该帧在环形缓存中的全局序号；未启用缓存时为 -1。 */
    private val onFrame: (ShortArray, Long) -> Unit,
    private val ringBuffer: AudioRingBuffer? = null,
) {
    @Volatile
    private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    /** 供播放端共享，让系统把播放信号作为 AEC 参考。 */
    val audioSessionId: Int get() = record?.audioSessionId ?: 0

    /** 设备是否提供系统级回声消除（自检展示用）。 */
    val aecSupported: Boolean get() = AcousticEchoCanceler.isAvailable()

    fun start() {
        if (running) return
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "AudioRecord.getMinBufferSize returned $minBuffer" }

        val recorder = AudioRecord.Builder()
            // VOICE_COMMUNICATION：驱动层强制 AEC/NS/AGC，是打断能工作的前提。
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, frameSamples * 8))
            .build()

        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
        record = recorder
        running = true
        recorder.startRecording()

        thread = Thread(
            {
                val buffer = ShortArray(frameSamples)
                while (running) {
                    val n = try {
                        recorder.read(buffer, 0, buffer.size)
                    } catch (e: Exception) {
                        VoiceState.set(error = "麦克风读取失败：${e.message}")
                        break
                    }
                    when {
                        n > 0 -> {
                            val frame = buffer.copyOf(n)
                            // 采集线程每帧都写入环形缓存（监听唤醒阶段也在写），
                            // 供唤醒后补灌漏掉的上行音频。
                            val seq = ringBuffer?.push(frame) ?: -1L
                            onFrame(frame, seq)
                        }
                        n < 0 -> {
                            VoiceState.set(error = "麦克风读取错误码 $n")
                            break
                        }
                    }
                }
            },
            "moss-audio-capture",
        ).apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(1_500)
        thread = null
        record?.runCatching {
            stop()
            release()
        }
        record = null
    }
}
