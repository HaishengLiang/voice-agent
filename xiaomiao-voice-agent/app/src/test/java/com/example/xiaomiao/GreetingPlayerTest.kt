package com.example.xiaomiao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GreetingPlayer 的 wav 解析纯逻辑单元测试（纯 JVM，反射调用私有方法）。
 * parseWav / downmixToMono / applyGain 都是字节处理、不碰 AudioTrack，
 * 但承担「48kHz 立体声 TTS 音降混播放不变调」的关键职责，值得锁定行为。
 */
class GreetingPlayerTest {
    private val player = GreetingPlayer(android.app.Application())

    private fun reflect(name: String, vararg paramTypes: Class<*>): Method =
        GreetingPlayer::class.java.getDeclaredMethod(name, *paramTypes).apply { isAccessible = true }

    /** 构造一个标准 44 字节头 + PCM16 data 的 wav 字节数组。 */
    private fun wav(sampleRate: Int, channels: Int, samples: IntArray): ByteArray {
        val data = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            data[i * 2] = (s and 0xFF).toByte()
            data[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        val out = ByteBuffer.allocate(44 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray(Charsets.US_ASCII))
        out.putInt(36 + data.size)
        out.put("WAVE".toByteArray(Charsets.US_ASCII))
        out.put("fmt ".toByteArray(Charsets.US_ASCII))
        out.putInt(16)               // fmt chunk size
        out.putShort(1)              // PCM
        out.putShort(channels.toShort())
        out.putInt(sampleRate)
        out.putInt(sampleRate * channels * 2)
        out.putShort((channels * 2).toShort())
        out.putShort(16)
        out.put("data".toByteArray(Charsets.US_ASCII))
        out.putInt(data.size)
        out.put(data)
        return out.array()
    }

    @Test
    fun `parseWav 单声道 解析采样率与 PCM 数据`() {
        val bytes = wav(16_000, 1, intArrayOf(0, 1000, -1000))
        val pcm = reflect("parseWav", ByteArray::class.java).invoke(player, bytes) as GreetingPlayer.Pcm
        assertEquals(16_000, pcm.sampleRate)
        assertEquals(6, pcm.data.size)
        assertEquals(1000, ByteBuffer.wrap(pcm.data).order(ByteOrder.LITTLE_ENDIAN).getShort(2).toInt())
    }

    @Test
    fun `parseWav 立体声 降混为单声道`() {
        // 左=1000 右=3000 → 平均 2000
        val bytes = wav(48_000, 2, intArrayOf(1000, 3000, -1000, -3000))
        val pcm = reflect("parseWav", ByteArray::class.java).invoke(player, bytes) as GreetingPlayer.Pcm
        assertEquals(48_000, pcm.sampleRate)
        assertEquals(4, pcm.data.size) // 2 帧 × 2 字节（已从立体声降为单声道）
        val bb = ByteBuffer.wrap(pcm.data).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2000, bb.getShort(0).toInt())
        assertEquals(-2000, bb.getShort(2).toInt())
    }

    @Test
    fun `parseWav 非法数据返回 null`() {
        val parse = reflect("parseWav", ByteArray::class.java)
        assertNull(parse.invoke(player, ByteArray(10))) // 太短
        assertNull(parse.invoke(player, ByteArray(60))) // 无有效 RIFF 块
    }

    @Test
    fun `downmixToMono 多声道取平均`() {
        val raw = wav(48_000, 2, intArrayOf(1000, 3000, 4000, 8000)).copyOfRange(44, 44 + 8)
        val mono = reflect("downmixToMono", ByteArray::class.java, Int::class.javaPrimitiveType!!)
            .invoke(player, raw, 2) as ByteArray
        assertEquals(4, mono.size) // 4 样本 / 2 声道 = 2 帧 × 2 字节
        val bb = ByteBuffer.wrap(mono).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2000, bb.getShort(0).toInt()) // (1000+3000)/2
        assertEquals(6000, bb.getShort(2).toInt()) // (4000+8000)/2
    }

    @Test
    fun `applyGain 增益并防止溢出削顶`() {
        val bytes = wav(16_000, 1, intArrayOf(1000, 30000)).copyOfRange(44, 44 + 4)
        val boosted = reflect("applyGain", ByteArray::class.java, Float::class.javaPrimitiveType!!)
            .invoke(player, bytes, 2.0f) as ByteArray
        val bb = ByteBuffer.wrap(boosted).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2000, bb.getShort(0).toInt())
        assertEquals(32767, bb.getShort(2).toInt()) // 30000*2 超过上限，削到 32767
    }

    @Test
    fun `Pcm data class 可正常构造`() {
        val pcm = GreetingPlayer.Pcm(ByteArray(2), 48_000)
        assertNotNull(pcm.data)
        assertEquals(48_000, pcm.sampleRate)
    }
}
