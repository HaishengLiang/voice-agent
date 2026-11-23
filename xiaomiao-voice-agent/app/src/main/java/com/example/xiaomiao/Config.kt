package com.example.xiaomiao

import android.content.Context
import android.content.SharedPreferences

/** 播放设备：外放扬声器，或听筒/耳机（含有线、USB、蓝牙 SCO）。 */
enum class AudioOutput { SPEAKER, HEADSET }

object Config {
    private const val PREF = "moss_wake"
    private const val KEY_API = "api_key"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_IDLE = "idle_timeout_ms"
    private const val KEY_USE_DEMO = "use_demo"
    private const val KEY_AUDIO_OUTPUT = "audio_output"
    private const val KEY_AUTO_START = "auto_start_on_open"
    private const val KEY_WAKE_WORD = "wake_word"

    const val DEFAULT_WAKE_WORD = "小喵小喵"

    // Agent 配置：人设 / 会话指令 / 对话音色。demo 模式忽略这些值（端点不接受自定义配置）。
    private const val KEY_PERSONA = "agent_persona"
    private const val KEY_INSTRUCTIONS = "agent_instructions"
    private const val KEY_DIALOGUE_VOICE = "agent_dialogue_voice"

    // 提示音配置：TTS 音色 + 两段提示音文本。demo 模式忽略，播自带 assets。
    private const val KEY_TTS_VOICE = "tone_tts_voice"
    private const val KEY_GREETING_TEXT = "tone_greeting_text"
    private const val KEY_FAREWELL_TEXT = "tone_farewell_text"
    // 默认值与 assets 里已覆盖的 TTS 提示音一致（同一音色、同一文本）。
    // 文本去掉逗号：MOSS TTS 遇中文逗号会拉出较长停顿，实测有无逗号总时长一样但逗号更拖；
    // 语速改由 TtsClient 的 expected_duration_sec 控制，这里只保留干净文本。
    const val DEFAULT_TTS_VOICE = "6c3152b1-1d4f-4e49-ade3-09f447712fec"
    const val DEFAULT_VOICE_NAME = "暖暖"
    const val DEFAULT_GREETING_TEXT = "在呢老板"
    const val DEFAULT_FAREWELL_TEXT = "好的我退下了"

    // TTS 生成结果保存在应用内部 filesDir，不打包进 apk，可随时重新生成。
    const val GREETING_FILE = "tone_greeting.wav"
    const val FAREWELL_FILE = "tone_farewell.wav"

    // 真实 API：需要 API Key，连上后主动发 attach_session。
    const val DEFAULT_ENDPOINT = "wss://api.mosi.cn/v1/realtime?model=moss-live-1.0"
    // 官网 demo：无需 Key，连上后服务端自动推 session_attached，切勿发 attach_session。
    const val DEMO_ENDPOINT = "wss://www.moss.cn/api/v1/public/website/moss-live/ws?voice=female"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!this::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        }
    }

    /** 默认走官网 demo（无需密钥）。关闭后使用自建/真实 API 与 API Key。 */
    var useDemo: Boolean
        get() = prefs.getBoolean(KEY_USE_DEMO, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_DEMO, value).apply()

    /** 播放设备，默认扬声器外放。 */
    var audioOutput: AudioOutput
        get() = if (prefs.getString(KEY_AUDIO_OUTPUT, "speaker") == "headset")
            AudioOutput.HEADSET else AudioOutput.SPEAKER
        set(value) = prefs.edit().putString(
            KEY_AUDIO_OUTPUT,
            if (value == AudioOutput.SPEAKER) "speaker" else "headset",
        ).apply()

    var apiKey: String
        // 首次安装使用构建时注入的密钥；用户在界面保存过之后以本机保存值为准。
        get() = prefs.getString(KEY_API, BuildConfig.MOSS_API_KEY) ?: BuildConfig.MOSS_API_KEY
        set(value) = prefs.edit().putString(KEY_API, value).apply()

    /**
     * 用户是否在本机显式保存过 API Key。
     * 区别于构建时注入的 Key：SDK 路径要求用户亲自「保存 Key」后才允许启动，
     * 避免用户以为在用自己的 Key、实际跑的是构建内置密钥。
     */
    val hasSavedApiKey: Boolean
        get() = prefs.contains(KEY_API) && !prefs.getString(KEY_API, "").isNullOrBlank()

    /** 根据当前模式返回连接地址。真实模式允许界面覆盖自定义 endpoint。 */
    val endpoint: String
        get() = if (useDemo) DEMO_ENDPOINT
                else (prefs.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT)

    var idleTimeoutMs: Long
        get() = prefs.getLong(KEY_IDLE, 30_000L)
        set(value) = prefs.edit().putLong(KEY_IDLE, value).apply()

    /**
     * 打开应用时自动开始监听。主页没有操作按钮，默认打开即用；
     * 需要保持安静（比如只想进设置改配置）时可在设置页关掉。
     */
    var autoStartOnOpen: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()

    /** 当前唤醒词（中文显示文本），词表文件由 KwsFiles 落盘。 */
    var wakeWord: String
        get() = prefs.getString(KEY_WAKE_WORD, DEFAULT_WAKE_WORD) ?: DEFAULT_WAKE_WORD
        set(value) = prefs.edit().putString(KEY_WAKE_WORD, value).apply()

    /** 自定义人设（对应 session.persona.prompt，最多 8000 Unicode 字符）。 */
    var persona: String
        get() = prefs.getString(KEY_PERSONA, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PERSONA, value).apply()

    /** 会话指令（对应 session.instructions，业务规则，与人设冲突时优先）。 */
    var instructions: String
        get() = prefs.getString(KEY_INSTRUCTIONS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_INSTRUCTIONS, value).apply()

    /** 实时对话音色 ID（对应 change_voice 的 voice_name）。 */
    var dialogueVoice: String
        get() = prefs.getString(KEY_DIALOGUE_VOICE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DIALOGUE_VOICE, value).apply()

    /** 提示音 TTS 音色 ID（对应 POST /v1/audio/speech 的 voice_id）。默认音色已用于覆盖 assets 自带提示音。 */
    var ttsVoice: String
        get() = prefs.getString(KEY_TTS_VOICE, DEFAULT_TTS_VOICE) ?: DEFAULT_TTS_VOICE
        set(value) = prefs.edit().putString(KEY_TTS_VOICE, value).apply()

    /** 联通提示音文本。 */
    var greetingText: String
        get() = prefs.getString(KEY_GREETING_TEXT, DEFAULT_GREETING_TEXT) ?: DEFAULT_GREETING_TEXT
        set(value) = prefs.edit().putString(KEY_GREETING_TEXT, value).apply()

    /** 挂断提示音文本。 */
    var farewellText: String
        get() = prefs.getString(KEY_FAREWELL_TEXT, DEFAULT_FAREWELL_TEXT) ?: DEFAULT_FAREWELL_TEXT
        set(value) = prefs.edit().putString(KEY_FAREWELL_TEXT, value).apply()

    /** 两段提示音文本是否都已填写（生成按钮的前置条件）。 */
    val hasToneTexts: Boolean
        get() = greetingText.isNotBlank() && farewellText.isNotBlank()

    /** 是否已有 TTS 生成的本地提示音文件。 */
    fun hasToneFile(context: Context, file: String): Boolean =
        context.applicationContext.fileList().contains(file)

    /** 删除已生成的提示音文件（文本改动后失效时调用）。 */
    fun deleteToneFile(context: Context, file: String) {
        context.applicationContext.deleteFile(file)
    }
}
