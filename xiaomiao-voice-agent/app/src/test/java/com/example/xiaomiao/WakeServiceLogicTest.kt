package com.example.xiaomiao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WakeService 纯逻辑单元测试（JVM）。
 * Service 生命周期/原生唤醒/AudioManager 需要真机或 Robolectric，故只测从实例方法里
 * 抽出的纯函数：道别词判定、上行音频字节转换、通话音量锚定计算——这三块是会话
 * 状态机里最容易出错的纯逻辑。
 */
class WakeServiceLogicTest {

    // ---------- isFarewell ----------

    @Test
    fun `isFarewell 识别中文道别词`() {
        assertTrue(WakeService.isFarewell("退下"))
        assertTrue(WakeService.isFarewell("好的，再见"))
        assertTrue(WakeService.isFarewell("那先这样吧"))
        assertTrue(WakeService.isFarewell("挂了"))
    }

    @Test
    fun `isFarewell 识别英文道别词且不区分大小写`() {
        assertTrue(WakeService.isFarewell("Goodbye"))
        assertTrue(WakeService.isFarewell("BYEBYE"))
        assertTrue(WakeService.isFarewell("see you")) // 去空白后 = seeyou
    }

    @Test
    fun `isFarewell 归一化标点与空格`() {
        assertTrue(WakeService.isFarewell("再 见"))
        assertTrue(WakeService.isFarewell("Good-bye!"))
        assertTrue(WakeService.isFarewell("拜拜～"))
        assertTrue(WakeService.isFarewell("不 聊 了。"))
    }

    @Test
    fun `isFarewell 普通文本与空白不误判`() {
        assertFalse(WakeService.isFarewell("今天天气不错"))
        assertFalse(WakeService.isFarewell(""))
        assertFalse(WakeService.isFarewell("   "))
        assertFalse(WakeService.isFarewell("！！！")) // 去标点后为空
    }

    @Test
    fun `isFarewell 采用包含匹配 可容忍口语修饰`() {
        // 实现是有意做包含匹配，"我再见了""那拜拜哦"这类带修饰的口语也能命中
        assertTrue(WakeService.isFarewell("我再见了"))
        assertTrue(WakeService.isFarewell("那拜拜哦"))
    }

    // ---------- pcm16ToBytes ----------

    @Test
    fun `pcm16ToBytes 小端序正确`() {
        val result = WakeService.pcm16ToBytes(shortArrayOf(0x0102.toShort(), 0xFFFE.toShort()))
        assertEquals(4, result.size)
        assertEquals(0x02.toByte(), result[0])
        assertEquals(0x01.toByte(), result[1])
        assertEquals(0xFE.toByte(), result[2])
        assertEquals(0xFF.toByte(), result[3])
    }

    @Test
    fun `pcm16ToBytes 负数按补码小端转换`() {
        val result = WakeService.pcm16ToBytes(shortArrayOf(-1)) // 0xFFFF
        assertEquals(0xFF.toByte(), result[0])
        assertEquals(0xFF.toByte(), result[1])
    }

    @Test
    fun `pcm16ToBytes 空帧返回空字节`() {
        assertEquals(0, WakeService.pcm16ToBytes(shortArrayOf()).size)
    }

    // ---------- computeCallVolume ----------

    @Test
    fun `computeCallVolume 按媒体音量比例锚定`() {
        // 媒体 7/16 ≈ 43.75%，通话范围 0..15 → 约 6
        assertEquals(6, WakeService.computeCallVolume(7, 16, 0, 15))
        // 媒体 8/16 = 50% → 通话约 7
        assertEquals(7, WakeService.computeCallVolume(8, 16, 0, 15))
    }

    @Test
    fun `computeCallVolume 限幅最高百分之80 永不顶满`() {
        // 媒体满格 → capped 0.8 → 通话 0..15 最高约 12，不是 15
        val full = WakeService.computeCallVolume(16, 16, 0, 15)!!
        assertTrue("不应顶满格", full < 15)
        assertEquals(12, full)
    }

    @Test
    fun `computeCallVolume 媒体音量为0时给小底噪`() {
        // ratio=0 → capped 0.12 → 通话 0..15 约 1，不是 0（保留可听底噪）
        val quiet = WakeService.computeCallVolume(0, 16, 0, 15)!!
        assertTrue("应有小底噪而非静音", quiet > 0)
        assertEquals(1, quiet)
    }

    @Test
    fun `computeCallVolume 支持非零最小音量`() {
        // callMin=2, callMax=10, 媒体满格 → capped 0.8 → 2 + 0.8*8 = 8
        assertEquals(8, WakeService.computeCallVolume(10, 10, 2, 10))
    }

    @Test
    fun `computeCallVolume 非法输入返回 null`() {
        assertNull(WakeService.computeCallVolume(5, 0, 0, 15)) // mediaMax <= 0
        assertNull(WakeService.computeCallVolume(5, 16, 10, 10)) // callMax <= callMin
        assertNull(WakeService.computeCallVolume(5, 16, 12, 10)) // callMax < callMin
    }

    @Test
    fun `computeCallVolume 媒体音量超过上限时钳制到100%再限幅`() {
        // mediaVol 超过 mediaMax → ratio 钳制 1.0 → capped 0.8 → 12
        assertEquals(12, WakeService.computeCallVolume(99, 16, 0, 15))
    }

    // ---------- 补灌诊断纯函数 ----------

    @Test
    fun `pcmPeak 取绝对值峰值且覆盖 Short_MIN_VALUE`() {
        assertEquals(0, WakeService.pcmPeak(shortArrayOf(0, 0)))
        assertEquals(3200, WakeService.pcmPeak(shortArrayOf(1200, -3200, 800)))
        assertEquals(32768, WakeService.pcmPeak(shortArrayOf(Short.MIN_VALUE)))
    }

    @Test
    fun `pcmRms 全静音为零且有声帧大于零`() {
        assertEquals(0, WakeService.pcmRms(listOf(ShortArray(640), ShortArray(640))))
        val loud = ShortArray(640) { 1000 }
        assertTrue("有声帧 RMS 应大于 0", WakeService.pcmRms(listOf(loud)) > 0)
        assertTrue("空列表 RMS 为 0", WakeService.pcmRms(emptyList()) == 0)
    }

    @Test
    fun `peaksCompact 按帧输出峰值序列`() {
        val frames = listOf(
            shortArrayOf(100, -100),
            shortArrayOf(0, 0),
            shortArrayOf(-500, 200),
        )
        assertEquals("100,0,500", WakeService.peaksCompact(frames))
    }
}
