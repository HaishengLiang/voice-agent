package com.example.xiaomiao

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * WakeDetector 真机 instrumentation 测试。
 *
 * 这里的价值是 JVM/Robolectric 无法覆盖的原生部分：KeywordSpotter 构造即加载 sherpa .so，
 * 只有在真机（arm64/armeabi）上才能跑。覆盖 init（模型落 filesDir + 构建 spotter）、
 * selfTest（wav 解析 + 命中）、accept/poll（帧喂入 → 解码 → 命中 → reset）整条链路。
 *
 * 前置：设备已在线（见 docs/adb-wireless-setup.md），默认词表为「小喵小喵」。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class WakeDetectorInstrumentedTest {
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        // 清掉可能残留的自定义自检样本，并写回默认「小喵小喵」词表
        File(ctx.filesDir, "wake_test.wav").delete()
        KwsFiles.resetKeywords(ctx)
    }

    @After
    fun tearDown() {
        File(ctx.filesDir, "wake_test.wav").delete()
        KwsFiles.resetKeywords(ctx)
    }

    /**
     * 核心用例：构造 detector（触发原生库加载 + 模型从 filesDir 加载），
     * 用默认样本跑自检，应命中「小喵小喵」。
     */
    @Test
    fun selfTest_defaultWav_hitsWakeWord() {
        val detector = WakeDetector(ctx)
        try {
            val result = detector.selfTest()
            assertTrue("自检应命中唤醒词，实际：$result", result.startsWith("命中："))
            assertTrue("应命中「小喵小喵」，实际：$result", result.contains("小喵"))
        } finally {
            detector.release()
        }
    }

    /**
     * 全链路：把默认 wav 的 PCM 帧按 20ms 分片喂进 accept，poll 应能命中。
     * 这验证了真实的唤醒检测路径（selfTest 用独立 stream，这里用主 stream）。
     */
    @Test
    fun acceptPoll_defaultWav_detectsKeyword() {
        val bytes = ctx.assets.open("greeting/xiaomiao_test.wav").use { it.readBytes() }
        val pcm = WakeDetector.wavPcm(bytes)
        assertNotNull("wav 解析不应为 null", pcm)
        pcm!!

        // 转成 FloatArray 后按 20ms（320 样本 @16k）分片喂入
        val samples = FloatArray(pcm.size / 2) { i ->
            ((pcm[i * 2 + 1].toInt() shl 8) or (pcm[i * 2].toInt() and 0xFF)).toShort() / 32768f
        }
        val frameSize = 320
        val detector = WakeDetector(ctx)
        try {
            var hit: String? = null
            var i = 0
            while (i < samples.size && hit == null) {
                val end = (i + frameSize).coerceAtMost(samples.size)
                val frame = ShortArray(end - i) { idx -> (samples[i + idx] * 32767).toInt().toShort() }
                detector.accept(frame)
                hit = detector.poll()
                i = end
            }
            // 尾部补静音，让解码器冲刷出最后几个 chunk
            for (k in 0 until 40) {
                if (hit != null) break
                detector.accept(ShortArray(frameSize))
                hit = detector.poll()
            }
            assertNotNull("连续喂入默认样本后应命中唤醒词", hit)
            assertTrue("命中词应含「小喵」，实际：$hit", hit!!.contains("小喵"))
        } finally {
            detector.release()
        }
    }

    /**
     * 静音不应误唤醒——真机上验证负样本零误报（离线验证过，这里确认原生链路一致）。
     */
    @Test
    fun acceptPoll_silence_noFalseWake() {
        val detector = WakeDetector(ctx)
        try {
            var hit: String? = null
            // 约 2 秒静音
            for (k in 0 until 100) {
                detector.accept(ShortArray(320))
                hit = detector.poll()
                if (hit != null) break
            }
            assertEquals("静音不应命中唤醒词，实际：$hit", null, hit)
        } finally {
            detector.release()
        }
    }

    /** wavPcm 纯函数也顺带在真机验证一次（JVM 已测，这里确认真机字节序一致）。 */
    @Test
    fun wavPcm_parsesDefaultSample() {
        val bytes = ctx.assets.open("greeting/xiaomiao_test.wav").use { it.readBytes() }
        val pcm = WakeDetector.wavPcm(bytes)
        assertNotNull(pcm)
        assertTrue("PCM 数据长度应大于 0", pcm!!.isNotEmpty())
        assertEquals("PCM 字节数应为偶数", 0, pcm.size % 2)
    }
}
