package com.example.xiaomiao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * KeywordGenerator 纯逻辑单元测试（JVM，无需 Android 设备/Context）。
 *
 * 用真实的 pinyin_map 抽样字构造内存表，验证音素拼接、三声变体、缺字校验、
 * 行数上限等核心口径。这些逻辑是运行时换词的正确性基石。
 */
class KeywordGeneratorTest {
    private val testMap = mapOf(
        '小' to "x iǎo|x iào",
        '喵' to "m iāo",
        '你' to "n ǐ",
        '好' to "h ǎo",
        '智' to "zh ì",
        '中' to "zh ōng",
        '文' to "w én",
        '天' to "t iān",
        '猫' to "m āo",
        '精' to "j īng",
        '灵' to "l íng",
        '大' to "d à",
        '家' to "j iā",
    )

    @Before
    fun setUp() {
        KeywordGenerator.injectMapForTest(testMap)
    }

    @Test
    fun `parseMap 正确解析多读音行`() {
        val map = KeywordGenerator.parseMap(
            sequenceOf("小=x iǎo|x iào", "喵=m iāo", "坏行无等号")
        )
        assertEquals("x iǎo|x iào", map['小'])
        assertEquals("m iāo", map['喵'])
        assertEquals(2, map.size)
    }

    @Test
    fun `小喵小喵 生成结果与现有 keywords 配置逐字一致`() {
        val r = KeywordGenerator.generate("小喵小喵")
        assertEquals("x iǎo m iāo x iǎo m iāo", r.phonetics)
        assertEquals(2, r.lineCount)
        assertEquals(
            "x iǎo m iāo x iǎo m iāo :1.0 #0.35 @小喵小喵\n" +
                "x iào m iāo x iào m iāo :1.0 #0.35 @小喵小喵\n",
            r.keywordsText,
        )
        assertTrue(r.missing.isEmpty())
    }

    @Test
    fun `天猫精灵 无声调变体时只生成一行`() {
        val r = KeywordGenerator.generate("天猫精灵")
        assertEquals("t iān m āo j īng l íng", r.phonetics)
        assertEquals(1, r.lineCount)
        assertTrue(r.keywordsText.startsWith("t iān m āo j īng l íng :1.0 #0.35 @天猫精灵"))
    }

    @Test
    fun `你好 两个三声字生成主行加两条四声变体`() {
        val r = KeywordGenerator.generate("你好")
        assertEquals("n ǐ h ǎo", r.phonetics)
        assertEquals(3, r.lineCount)
        assertTrue(r.keywordsText.contains("n ì h ǎo")) // 只「你」变四声
        assertTrue(r.keywordsText.contains("n ǐ h ào")) // 只「好」变四声
    }

    @Test
    fun `重复三声字按字去重 小喵小喵只多一行变体`() {
        val r = KeywordGenerator.generate("小喵小喵")
        // 「小」出现两次但只算一个变体字，故总共 2 行而非 3 行
        assertEquals(2, r.lineCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `生僻字不在映射表时抛异常`() {
        KeywordGenerator.generate("小喵龘") // 龘不在 testMap
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空唤醒词抛异常`() {
        KeywordGenerator.generate("   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `超过12字抛异常`() {
        KeywordGenerator.generate("小喵小喵小喵小喵小喵小喵小") // 13 字
    }

    @Test
    fun `变体行数不超过 MAX_LINES 上限`() {
        // 12 个互不相同的字（不超长度上限）、且都带三声，变体需求 12 > 8，应被截断
        val manyTone3 = listOf('丙', '秉', '柄', '饼', '炳', '拨', '波', '播', '伯', '泊', '博', '搏')
        val bigMap = manyTone3.associateWith { "x iǎo" } + testMap
        KeywordGenerator.injectMapForTest(bigMap)
        val r = KeywordGenerator.generate(manyTone3.joinToString(""))
        assertEquals(KeywordGenerator.MAX_LINES, r.lineCount)
    }
}
