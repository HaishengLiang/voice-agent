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

    /** 根据当前模式返回连接地址。真实模式允许界面覆盖自定义 endpoint。 */
    val endpoint: String
        get() = if (useDemo) DEMO_ENDPOINT
                else (prefs.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT)

    var idleTimeoutMs: Long
        get() = prefs.getLong(KEY_IDLE, 30_000L)
        set(value) = prefs.edit().putLong(KEY_IDLE, value).apply()
}
