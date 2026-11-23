package com.example.xiaomiao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WakeDetector 纯逻辑单元测试（JVM）。
 * 原生 spotter 依赖 sherpa .so 无法在 JVM 加载，故测可独立验证的 wavPcm 解析——
 * 这是自检（selfTest）读取唤醒词样本的核心，解析错了自检永远失败。
 */
class WakeDetectorTest {
    /** 构造带标准 RIFF 头的 wav（data 块在前或在后均可测）。 */
    private fun wav(samples: ByteArray, sampleRate: Int = 16_000): ByteArray {
        val out = ByteBuffer.allocate(44 + samples.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray(Charsets.US_ASCII))
        out.putInt(36 + samples.size)
        out.put("WAVE".toByteArray(Charsets.US_ASCII))
        out.put("fmt ".toByteArray(Charsets.US_ASCII))
        out.putInt(16)
        out.putShort(1) // PCM
        out.putShort(1) // mono
        out.putInt(sampleRate)
        out.putInt(sampleRate * 2)
        out.putShort(2)
        out.putShort(16)
        out.put("data".toByteArray(Charsets.US_ASCII))
        out.putInt(samples.size)
        out.put(samples)
        return out.array()
    }

    @Test
    fun `wavPcm 正确提取 data 块 PCM 数据`() {
        val pcm = byteArrayOf(0, 1, 2, 3, 4, 5)
        val result = WakeDetector.wavPcm(wav(pcm))
        assertEquals(pcm.size, result?.size)
        assertEquals(0.toByte(), result!![0])
        assertEquals(5.toByte(), result[5])
    }

    @Test
    fun `wavPcm data 块前有额外 chunk 时跳过`() {
        // 在 fmt 与 data 之间插一个 LIST chunk（偶数长度），解析器应跳过
        val pcm = byteArrayOf(10, 20, 30, 40)
        val base = wav(pcm)
        val insertPos = 36 // fmt 块结束处
        val listChunk = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        listChunk.put("LIST".toByteArray(Charsets.US_ASCII))
        listChunk.putInt(4)
        listChunk.putInt(0)
        val withExtra = base.copyOfRange(0, insertPos) + listChunk.array() + base.copyOfRange(insertPos, base.size)
        val result = WakeDetector.wavPcm(withExtra)
        assertEquals(pcm.size, result?.size)
        assertEquals(10.toByte(), result!![0])
    }

    @Test
    fun `wavPcm 无 data 块返回 null`() {
        val noData = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        noData.put("RIFF".toByteArray(Charsets.US_ASCII))
        noData.putInt(32)
        noData.put("WAVE".toByteArray(Charsets.US_ASCII))
        assertNull(WakeDetector.wavPcm(noData.array()))
    }

    @Test
    fun `wavPcm 过短输入返回 null`() {
        assertNull(WakeDetector.wavPcm(ByteArray(8)))
    }

    @Test
    fun `wavPcm 空 data 块返回空数组`() {
        val result = WakeDetector.wavPcm(wav(ByteArray(0)))
        assertEquals(0, result?.size)
    }
}
