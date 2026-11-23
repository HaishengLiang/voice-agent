package com.example.xiaomiao

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * KwsFiles 单元测试（Robolectric，无需真机）。
 *
 * 覆盖运行时换词的文件管理层：模型从 assets 复制到 filesDir、版本戳避免重复复制、
 * 默认词表写入、自定义词表读写与恢复默认。这是「不重装 APK 换词」的落地基础。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33]) // 固定 SDK，避免 Robolectric 拉取最新 android-all 镜像
class KwsFilesTest {
    private lateinit var context: Context
    private lateinit var kwsDir: File
    private lateinit var versionFile: File

    private val modelFiles = listOf(
        "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        "tokens.txt",
        "pinyin_map.txt",
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        kwsDir = File(context.filesDir, "kws")
        versionFile = File(context.filesDir, "kws.version")
    }

    @Test
    fun `ensureReady 首次调用复制全部模型并写默认词表和版本戳`() {
        val dir = KwsFiles.ensureReady(context)
        assertEquals(kwsDir, dir)
        modelFiles.forEach { name ->
            val f = File(dir, name)
            assertTrue("模型文件 $name 应存在", f.exists())
            assertTrue("模型文件 $name 不应为空", f.length() > 0)
        }
        assertTrue("默认词表应写入", File(dir, "keywords.txt").exists())
        assertEquals("1", versionFile.readText().trim())
    }

    @Test
    fun `ensureReady 版本一致时不删除用户已有文件`() {
        KwsFiles.ensureReady(context)
        val custom = "n ǐ h ǎo :1.0 #0.35 @你好\n"
        KwsFiles.writeKeywords(context, custom)
        // 再次 ensureReady：版本戳一致，不应覆盖用户词表
        KwsFiles.ensureReady(context)
        assertEquals(custom, KwsFiles.keywordsFile(context).readText())
    }

    @Test
    fun `ensureReady 版本变化时重新复制模型`() {
        KwsFiles.ensureReady(context)
        val mapFile = File(kwsDir, "pinyin_map.txt")
        mapFile.writeText("CORRUPTED") // 模拟模型损坏 / 版本升级前的旧文件
        versionFile.writeText("0")     // 版本戳不匹配

        KwsFiles.ensureReady(context)

        assertTrue("应从 assets 重新复制覆盖损坏文件", mapFile.length() > "CORRUPTED".length)
        assertEquals("1", versionFile.readText().trim())
    }

    @Test
    fun `writeKeywords 写入的内容可被读回`() {
        KwsFiles.ensureReady(context)
        val text = "t iān m āo :1.0 #0.35 @天猫\n"
        val file = KwsFiles.writeKeywords(context, text)
        assertEquals(text, file.readText())
        assertEquals(file, KwsFiles.keywordsFile(context))
    }

    @Test
    fun `hasCustomKeywords 区分默认与自定义词表`() {
        KwsFiles.ensureReady(context)
        assertFalse(KwsFiles.hasCustomKeywords(context))
        KwsFiles.writeKeywords(context, "n ǐ h ǎo :1.0 #0.35 @你好\n")
        assertTrue(KwsFiles.hasCustomKeywords(context))
    }

    @Test
    fun `resetKeywords 恢复默认后 hasCustomKeywords 为 false`() {
        KwsFiles.ensureReady(context)
        KwsFiles.writeKeywords(context, "n ǐ h ǎo :1.0 #0.35 @你好\n")
        assertTrue(KwsFiles.hasCustomKeywords(context))

        val reset = KwsFiles.resetKeywords(context)
        assertTrue(reset.readText().contains("@小喵小喵"))
        assertFalse(KwsFiles.hasCustomKeywords(context))
    }
}
