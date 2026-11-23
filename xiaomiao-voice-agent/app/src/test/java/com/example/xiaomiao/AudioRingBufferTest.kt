package com.example.xiaomiao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AudioRingBuffer 单元测试（纯 JVM，无 Android 依赖）。
 * 环形缓存是「唤醒后补灌第一句话」的核心，重点验证帧序号边界与覆盖语义。
 */
class AudioRingBufferTest {
    private fun frame(v: Int) = ShortArray(2) { v.toShort() }

    @Test
    fun `空缓存 latestSeq 为 -1 且 range 返回空`() {
        val buf = AudioRingBuffer(capacityFrames = 4)
        assertEquals(-1L, buf.latestSeq())
        assertTrue(buf.range(-1, 5).isEmpty())
    }

    @Test
    fun `push 返回递增序号并可按范围读取`() {
        val buf = AudioRingBuffer(capacityFrames = 4)
        assertEquals(0L, buf.push(frame(0)))
        assertEquals(1L, buf.push(frame(1)))
        assertEquals(2L, buf.push(frame(2)))
        assertEquals(2L, buf.latestSeq())
        // (0, 2] → 序号 1、2 两帧
        val got = buf.range(0, 2)
        assertEquals(2, got.size)
        assertEquals(1.toShort(), got[0][0])
        assertEquals(2.toShort(), got[1][0])
    }

    @Test
    fun `range 的 fromSeqExclusive 语义 不含起点`() {
        val buf = AudioRingBuffer(capacityFrames = 4)
        repeat(3) { buf.push(frame(it)) }
        assertTrue(buf.range(0, 0).isEmpty())      // (0,0] 空区间
        assertEquals(1, buf.range(1, 2).size)      // (1,2] → 序号 2
        assertTrue(buf.range(2, 2).isEmpty())      // 空区间
        assertTrue(buf.range(3, 2).isEmpty())      // 逆序区间
    }

    @Test
    fun `超出容量的旧帧被覆盖自动跳过`() {
        val buf = AudioRingBuffer(capacityFrames = 3)
        repeat(5) { buf.push(frame(it)) } // 序号 0..4，容量 3 → 有效 2,3,4
        // 请求 (0,4]，最老帧 = nextSeq-capacity = 5-3 = 2，应只返回 2,3,4
        val got = buf.range(0, 4)
        assertEquals(3, got.size)
        assertEquals(2.toShort(), got[0][0])
        assertEquals(4.toShort(), got[2][0])
    }

    @Test
    fun `push 复制入参 外部修改原数组不影响缓存`() {
        val buf = AudioRingBuffer(capacityFrames = 4)
        val f = frame(9)
        buf.push(f)
        f[0] = 123 // 模拟采集 buffer 复用被覆盖
        assertEquals(9.toShort(), buf.range(-1, 0)[0][0])
    }

    @Test
    fun `clear 清空帧并重置序号`() {
        val buf = AudioRingBuffer(capacityFrames = 4)
        repeat(3) { buf.push(frame(it)) }
        buf.clear()
        assertEquals(-1L, buf.latestSeq())
        assertTrue(buf.range(-1, 2).isEmpty())
        assertEquals(0L, buf.push(frame(7))) // 序号从 0 重新开始
    }
}
