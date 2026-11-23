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
import org.robolectric.annotation.Config as RobolectricConfig

/**
 * Config 单元测试（Robolectric）。
 * 验证 SharedPreferences 读写口径：默认值、往返、模式切换、派生属性、本地文件操作。
 */
@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(sdk = [33])
class ConfigTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Config 是单例、prefs 是 lateinit：Robolectric 每个测试重建 application，
        // 但 Config 实例不变，prefs 会残留指向旧 context。用反射强制重绑到当前 context。
        val prefsField = Config::class.java.getDeclaredField("prefs")
        prefsField.isAccessible = true
        prefsField.set(
            Config,
            context.applicationContext.getSharedPreferences("moss_wake", Context.MODE_PRIVATE),
        )
        context.getSharedPreferences("moss_wake", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `默认值符合预期`() {
        assertTrue(Config.useDemo)
        assertEquals(Config.DEFAULT_WAKE_WORD, Config.wakeWord)
        assertEquals(30_000L, Config.idleTimeoutMs)
        assertTrue(Config.autoStartOnOpen)
        assertEquals(AudioOutput.SPEAKER, Config.audioOutput)
        assertEquals(Config.DEMO_ENDPOINT, Config.endpoint)
        assertEquals(Config.DEFAULT_TTS_VOICE, Config.ttsVoice)
        assertEquals(Config.DEFAULT_GREETING_TEXT, Config.greetingText)
        assertEquals("", Config.persona)
        assertEquals("", Config.instructions)
        assertEquals("", Config.dialogueVoice)
    }

    @Test
    fun `字符串字段读写往返`() {
        Config.wakeWord = "你好小智"
        Config.persona = "你是一个助手"
        Config.instructions = "简洁回答"
        Config.dialogueVoice = "voice-123"
        Config.ttsVoice = "tts-456"
        Config.greetingText = "在呢"
        Config.farewellText = "再见"
        assertEquals("你好小智", Config.wakeWord)
        assertEquals("你是一个助手", Config.persona)
        assertEquals("简洁回答", Config.instructions)
        assertEquals("voice-123", Config.dialogueVoice)
        assertEquals("tts-456", Config.ttsVoice)
        assertEquals("在呢", Config.greetingText)
        assertEquals("再见", Config.farewellText)
    }

    @Test
    fun `endpoint 随 demo 模式切换 真实模式可覆盖`() {
        assertTrue(Config.useDemo)
        assertEquals(Config.DEMO_ENDPOINT, Config.endpoint)
        Config.useDemo = false
        assertEquals(Config.DEFAULT_ENDPOINT, Config.endpoint)
        // 真实模式允许自定义 endpoint
        context.getSharedPreferences("moss_wake", Context.MODE_PRIVATE)
            .edit().putString("endpoint", "wss://custom.example/ws").commit()
        assertEquals("wss://custom.example/ws", Config.endpoint)
        // 切回 demo 又忽略自定义值
        Config.useDemo = true
        assertEquals(Config.DEMO_ENDPOINT, Config.endpoint)
    }

    @Test
    fun `audioOutput 读写 SPEAKER 与 HEADSET`() {
        Config.audioOutput = AudioOutput.HEADSET
        assertEquals(AudioOutput.HEADSET, Config.audioOutput)
        Config.audioOutput = AudioOutput.SPEAKER
        assertEquals(AudioOutput.SPEAKER, Config.audioOutput)
    }

    @Test
    fun `hasSavedApiKey 只有显式保存非空 key 才为 true`() {
        assertFalse(Config.hasSavedApiKey)
        Config.apiKey = ""
        assertFalse(Config.hasSavedApiKey)
        Config.apiKey = "sk-real-key"
        assertTrue(Config.hasSavedApiKey)
        assertEquals("sk-real-key", Config.apiKey)
    }

    @Test
    fun `hasToneTexts 两段文本都非空才为 true`() {
        assertTrue(Config.hasToneTexts) // 默认两段都有值
        Config.greetingText = ""
        assertFalse(Config.hasToneTexts)
        Config.greetingText = "在呢"
        Config.farewellText = "  "
        assertFalse(Config.hasToneTexts)
        Config.farewellText = "再见"
        assertTrue(Config.hasToneTexts)
    }

    @Test
    fun `提示音本地文件的存在判断与删除`() {
        assertFalse(Config.hasToneFile(context, Config.GREETING_FILE))
        context.openFileOutput(Config.GREETING_FILE, Context.MODE_PRIVATE).use { it.write("wav".toByteArray()) }
        assertTrue(Config.hasToneFile(context, Config.GREETING_FILE))
        Config.deleteToneFile(context, Config.GREETING_FILE)
        assertFalse(Config.hasToneFile(context, Config.GREETING_FILE))
    }

    @Test
    fun `idleTimeoutMs 与 autoStartOnOpen 读写往返`() {
        Config.idleTimeoutMs = 60_000L
        Config.autoStartOnOpen = false
        assertEquals(60_000L, Config.idleTimeoutMs)
        assertFalse(Config.autoStartOnOpen)
    }
}
