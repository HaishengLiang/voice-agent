package com.example.xiaomiao

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject

class WakeService : Service() {
    companion object {
        const val ACTION_START = "com.example.xiaomiao.START"
        const val ACTION_STOP = "com.example.xiaomiao.STOP"
        const val ACTION_WAKE = "com.example.xiaomiao.WAKE"
        const val ACTION_END_SESSION = "com.example.xiaomiao.END_SESSION"
        const val ACTION_UPDATE_AUDIO = "com.example.xiaomiao.UPDATE_AUDIO"

        const val CHANNEL_ID = "moss_wake_channel"
        private const val NOTIFICATION_ID = 1001

        // 用户道别关键词：命中后主动结束会话、关闭 WebSocket、回到 KWS 唤醒监听
        private val FAREWELL_KEYWORDS = listOf(
            "退下", "再见", "拜拜", "先这样", "就这样吧", "结束对话", "挂了", "不聊了",
            "goodbye", "byebye", "bye", "seeyou",
        )

        fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "常驻语音唤醒",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = "离线唤醒词监听与 MOSS-Live 语音对话" }
                )
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var detector: WakeDetector? = null
    private var capture: AudioCapture? = null
    private var player: AudioPlayer? = null
    private var greetingPlayer: GreetingPlayer? = null
    private var client: MossRealtimeClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var savedAudioMode: Int? = null
    private var savedSpeakerphone: Boolean? = null
    private var savedVoiceVolume: Int? = null

    // 播放本地问候音期间不上传麦克风，避免把问候音当用户输入。
    @Volatile
    private var suppressMicUntilMs = 0L

    private var phase = VoiceState.Phase.IDLE
    private var pendingChunks = 0
    private var audioDonePending = false
    private var currentRunId = ""
    private var currentTurnId = ""

    private val idleRunnable = Runnable { endSession("空闲超时，已回到唤醒监听") }

    override fun onCreate() {
        super.onCreate()
        Config.init(this)
        createChannel(this)
        startForeground(NOTIFICATION_ID, notification("准备中..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Config.init(this)
        when (intent?.action) {
            ACTION_STOP -> stopListening()
            ACTION_WAKE -> {
                if (capture == null) startListening()
                mainHandler.post { startSession("手动唤醒") }
            }
            ACTION_END_SESSION -> {
                // 手动结束当前对话，但保持 KWS 常驻监听（区别于「停止监听」）。
                mainHandler.post {
                    if (phase == VoiceState.Phase.SESSION_CONNECTING ||
                        phase == VoiceState.Phase.SESSION_ACTIVE
                    ) {
                        endSession("已手动结束对话，回到唤醒监听")
                    }
                }
            }
            ACTION_UPDATE_AUDIO -> applyOutputRouting()
            else -> startListening()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("小喵语音智能")
            .setContentText(text)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    private fun createPlayer(): AudioPlayer = AudioPlayer(
        mainHandler,
        capture?.audioSessionId ?: 0,
    ) { meta ->
        val chunkId = meta.chunkId
        if (!chunkId.isNullOrBlank()) {
            client?.sendJson(
                "playback.chunk.completed",
                "run_id" to meta.runId,
                "turn_id" to meta.turnId,
                "chunk_id" to chunkId,
            )
        }
        pendingChunks = (pendingChunks - 1).coerceAtLeast(0)
        if (audioDonePending && pendingChunks == 0) {
            audioDonePending = false
            client?.sendJson(
                "playback.completed",
                "run_id" to currentRunId,
                "turn_id" to currentTurnId,
            )
        }
    }

    private fun startListening() {
        if (capture != null) return
        try {
            configureAudioForAec()
            detector = WakeDetector(applicationContext)
            capture = AudioCapture { frame -> onAudioFrame(frame) }
            capture?.start()
            player = createPlayer()
            greetingPlayer = GreetingPlayer(applicationContext)

            val powerManager = getSystemService(PowerManager::class.java)
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mosswake:kws")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }

            phase = VoiceState.Phase.LISTENING_WAKE
            VoiceState.clearTranscript()
            VoiceState.set(VoiceState.Phase.LISTENING_WAKE, "等待唤醒词...")
            updateNotification("离线唤醒监听中")
        } catch (e: Throwable) {
            VoiceState.set(
                VoiceState.Phase.IDLE,
                "启动失败",
                "KWS 初始化失败：${e.message ?: e.javaClass.simpleName}",
            )
            stopSelf()
        }
    }

    /**
     * 配置语音通信会话，让系统 AEC 生效：
     * - MODE_IN_COMMUNICATION：多数设备需要它才真正启用消除器；
     * - 输出设备：按配置走扬声器或耳机/听筒（见 applyOutputRouting）；
     * - 音量：播放走 STREAM_VOICE_CALL。早期版本曾把该流 setStreamVolume(max) 顶到满格，
     *   满格值会留在系统里不会自动回落，导致进入通信模式后声音突然很大。这里按媒体音量
     *   比例把通话音量重新锚定到合理水平（并限幅，永不顶满），真正跟随用户的系统音量。
     */
    private fun configureAudioForAec() {
        val am = getSystemService(AudioManager::class.java) ?: return
        audioManager = am
        savedAudioMode = am.mode
        @Suppress("DEPRECATION")
        savedSpeakerphone = am.isSpeakerphoneOn
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            applyOutputRouting()
            alignCallVolumeToMedia(am)
            // 记录锚定后的值，停止监听时恢复到这个合理水平，而不是历史遗留的满格。
            savedVoiceVolume = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        } catch (e: Throwable) {
            VoiceState.set(error = "音频通道配置失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 把通话音量按媒体音量的相对比例锚定，避免顶到满格。
     * 用户媒体音量 7/16≈44% → 通话音量也落在约 44%（限幅最高 80%，永远不满格）。
     * 这样 AI 语音/问候音的响度与用户在系统里设置的媒体音量感受一致。
     */
    private fun alignCallVolumeToMedia(am: AudioManager) {
        val mediaMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val mediaVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val callMax = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        val callMin = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            am.getStreamMinVolume(AudioManager.STREAM_VOICE_CALL)
        } else 0
        if (mediaMax <= 0 || callMax <= callMin) return
        val ratio = (mediaVol.toFloat() / mediaMax.toFloat()).coerceIn(0f, 1f)
        // 留安全余量：最高只到 80%，绝不顶满格刺耳；媒体音量为 0 时给一个很小的可听底噪。
        val capped = if (ratio <= 0f) 0.12f else ratio.coerceAtMost(0.8f)
        val target = (callMin + capped * (callMax - callMin)).toInt().coerceIn(callMin, callMax)
        if (am.getStreamVolume(AudioManager.STREAM_VOICE_CALL) != target) {
            @Suppress("DEPRECATION")
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0)
        }
    }

    /**
     * 按 Config.audioOutput 选择输出设备。
     * - 扬声器：API31+ 用 setCommunicationDevice(BUILTIN_SPEAKER)，低版本用 setSpeakerphoneOn(true)。
     * - 耳机/听筒：API31+ 优先精确选有线/USB/蓝牙设备，找不到就 clearCommunicationDevice 走系统默认；
     *              低版本用 setSpeakerphoneOn(false)，系统会自动路由到已连接的耳机或听筒。
     * 注意：一旦用过 setCommunicationDevice，就不要再混用 setSpeakerphoneOn，故按 API 分叉。
     */
    private fun applyOutputRouting() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (Config.audioOutput) {
                    AudioOutput.SPEAKER -> {
                        val speaker = am.availableCommunicationDevices
                            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        if (speaker != null && am.setCommunicationDevice(speaker)) return
                        // 兜底：老式 speakerphone 开关
                        @Suppress("DEPRECATION")
                        am.isSpeakerphoneOn = true
                    }
                    AudioOutput.HEADSET -> {
                        val preferred = am.availableCommunicationDevices.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        }
                        if (preferred != null) {
                            am.setCommunicationDevice(preferred)
                        } else {
                            // 无可用耳机设备时回到系统默认（听筒），并清掉可能残留的扬声器指定
                            am.clearCommunicationDevice()
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = Config.audioOutput == AudioOutput.SPEAKER
            }
        } catch (e: Throwable) {
            VoiceState.set(error = "切换输出设备失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun restoreAudioSettings() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            }
            savedVoiceVolume?.let {
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, it, 0)
            }
            @Suppress("DEPRECATION")
            savedSpeakerphone?.let { am.isSpeakerphoneOn = it }
            savedAudioMode?.let { am.mode = it }
        } catch (_: Throwable) {
        }
        audioManager = null
    }

    private fun onAudioFrame(frame: ShortArray) {
        when (phase) {
            VoiceState.Phase.LISTENING_WAKE -> {
                try {
                    val detector = this.detector ?: return
                    detector.accept(frame)
                    val keyword = detector.poll()
                    if (keyword != null) {
                        mainHandler.post { startSession("命中唤醒词：$keyword") }
                    }
                } catch (e: Throwable) {
                    mainHandler.post {
                        VoiceState.set(error = "唤醒检测失败：${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
            VoiceState.Phase.SESSION_CONNECTING, VoiceState.Phase.SESSION_ACTIVE -> {
                // 本地问候音播放期间不上传，避免设备外放被麦克风采到当成用户输入。
                if (System.currentTimeMillis() < suppressMicUntilMs) return
                val bytes = ByteArray(frame.size * 2)
                for (i in frame.indices) {
                    val value = frame[i].toInt() and 0xFFFF
                    bytes[i * 2] = (value and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
                }
                client?.sendBytes(bytes)
            }
            else -> Unit
        }
    }

    private fun startSession(reason: String) {
        if (phase == VoiceState.Phase.SESSION_CONNECTING || phase == VoiceState.Phase.SESSION_ACTIVE) return
        // demo 模式无需密钥；真实 API 模式才需要。
        if (!Config.useDemo && Config.apiKey.isBlank()) {
            VoiceState.set(error = "请先在界面填写 MOSS API Key，或切换回 demo 模式")
            return
        }

        // 每次唤醒开启新一轮会话：清空上一轮的对话列表，从空白开始记录本轮对话。
        VoiceState.clearTranscript()
        phase = VoiceState.Phase.SESSION_CONNECTING
        mainHandler.removeCallbacks(idleRunnable)
        pendingChunks = 0
        audioDonePending = false
        player?.release()
        player = createPlayer()
        player?.start()

        // 新一轮开始：停掉上一轮可能仍在播放的挂断音，避免与问候音叠加。
        greetingPlayer?.stopFarewell()
        // 唤醒后立即播放本地问候音（与 HTML demo 一致），播放期间不上传麦克风。
        val greetingMs = greetingPlayer?.play() ?: 0L
        if (greetingMs > 0) {
            suppressMicUntilMs = System.currentTimeMillis() + greetingMs + 200L
            updateNotification("已唤醒，正在接入...")
            VoiceState.set(VoiceState.Phase.SESSION_CONNECTING, "$reason，正在接入...")
        }

        client = MossRealtimeClient(
            endpoint = Config.endpoint,
            apiKey = Config.apiKey,
            onTextEvent = { action, data -> mainHandler.post { handleEvent(action, data) } },
            onBinaryAudio = { bytes -> mainHandler.post { playAudio(bytes) } },
            onConnectionState = { state -> mainHandler.post { handleConnectionState(state) } },
            useDemo = Config.useDemo,
        )
        client?.connect()
        VoiceState.set(
            VoiceState.Phase.SESSION_CONNECTING,
            "$reason，正在连接 ${if (Config.useDemo) "官网 demo" else "MOSS-Live"}...",
        )
        updateNotification("正在连接 ${if (Config.useDemo) "官网 demo" else "MOSS-Live"}...")
    }

    /** 用户说完道别词（如「再见」「退下吧」）时主动结束会话，回到 KWS 监听。 */
    private fun isFarewell(text: String): Boolean {
        if (text.isBlank()) return false
        // 归一化：去空白与常见中英文标点，转小写，避免「再 见」「Good-bye!」漏判
        val normalized = text.lowercase().replace(Regex("[\\s，。！？、,.!?~～；;：:\"'“”‘’—\\-]"), "")
        if (normalized.isBlank()) return false
        return FAREWELL_KEYWORDS.any { normalized.contains(it) }
    }

    private fun handleConnectionState(state: String) {
        // endSession 主动 close 后，旧连接会迟到 "closed"/"error" 回调，
        // 此时已回到 LISTENING_WAKE，忽略以避免覆盖「已回到唤醒监听」状态文案。
        if (phase != VoiceState.Phase.SESSION_CONNECTING &&
            phase != VoiceState.Phase.SESSION_ACTIVE
        ) return

        if (state == "connected") {
            // 真实 API 需要主动 attach_session；官网 demo 连上即自动推 session_attached，
            // 若再发 attach_session 会被拒绝（invalid_public_request）。
            if (!Config.useDemo) client?.sendJson("attach_session", "session_id" to (null as Any?))
        } else {
            VoiceState.set(
                status = "连接状态：$state",
                error = if (state.startsWith("error")) state else null,
            )
        }
    }

    private fun playAudio(bytes: ByteArray) {
        val meta = client?.pollAudioMeta()
            ?: MossRealtimeClient.AudioMeta("", "", null)
        if (meta.chunkId != null) pendingChunks++
        player?.play(bytes, meta)
    }

    private fun handleEvent(action: String, data: JSONObject) {
        when (action) {
            "session_attached" -> {
                val error = data.optString("error")
                if (error.isNotBlank()) {
                    VoiceState.set(error = "会话配置失败：$error")
                    endSession("会话建立失败，已回到唤醒监听")
                    return
                }
                client?.sendJson("mic_state", "enabled" to true)
                phase = VoiceState.Phase.SESSION_ACTIVE
                VoiceState.set(VoiceState.Phase.SESSION_ACTIVE, "对话中，可直接说话或打断")
                updateNotification("MOSS-Live 对话中")
            }
            "session.updated" -> {
                val error = data.optString("error")
                if (error.isNotBlank()) VoiceState.set(error = "会话更新失败：$error")
            }
            "input.transcript.updated" -> {
                VoiceState.setUserText(data.optString("text"))
                scheduleIdle()
            }
            "input.transcript.done" -> {
                val text = data.optString("text")
                VoiceState.setUserText(text, done = true)
                if (isFarewell(text)) {
                    endSession("用户道别，已回到唤醒监听")
                } else {
                    scheduleIdle()
                }
            }
            "output.transcript.updated" -> {
                VoiceState.setModelText(data.optString("text"))
                scheduleIdle()
            }
            "output.transcript.done" -> {
                VoiceState.setModelText(data.optString("text"), done = true)
                scheduleIdle()
            }
            "response.audio.started" -> player?.start()
            "response.audio.done" -> {
                currentRunId = data.optString("run_id").ifBlank { currentRunId }
                currentTurnId = data.optString("turn_id").ifBlank { currentTurnId }
                audioDonePending = true
                if (pendingChunks == 0) {
                    audioDonePending = false
                    client?.sendJson(
                        "playback.completed",
                        "run_id" to currentRunId,
                        "turn_id" to currentTurnId,
                    )
                }
                scheduleIdle()
            }
            "response.audio.stop" -> {
                val runId = data.optString("run_id")
                val turnId = data.optString("turn_id")
                player?.stopAndClear()
                pendingChunks = 0
                audioDonePending = false
                client?.sendJson(
                    "playback.stopped",
                    "run_id" to runId,
                    "turn_id" to turnId,
                    "played_audio_ms" to (player?.playedAudioMs() ?: 0L),
                )
            }
            "response.interrupted" -> {
                player?.stopAndClear()
                pendingChunks = 0
                audioDonePending = false
                scheduleIdle()
            }
            "response.ended" -> scheduleIdle()
            "error" -> VoiceState.set(error = data.optString("message").ifBlank { "MOSS 返回错误" })
        }
    }

    private fun scheduleIdle() {
        mainHandler.removeCallbacks(idleRunnable)
        mainHandler.postDelayed(idleRunnable, Config.idleTimeoutMs)
    }

    private fun endSession(reason: String, playFarewell: Boolean = true) {
        mainHandler.removeCallbacks(idleRunnable)
        client?.close()
        client = null
        greetingPlayer?.stop()
        player?.release()
        player = null
        pendingChunks = 0
        audioDonePending = false
        phase = VoiceState.Phase.LISTENING_WAKE
        // 会话结束：清空本轮对话列表，回到等待下一次唤醒。
        VoiceState.clearTranscript()
        VoiceState.set(VoiceState.Phase.LISTENING_WAKE, reason, error = null)
        updateNotification("离线唤醒监听中")
        // 播放挂断提示音，让用户在没有界面时也能确认「对话已结束」。
        // 停止监听（stopListening）会传 false——那时音频设置即将还原、服务即将销毁。
        if (playFarewell) greetingPlayer?.playFarewell()
    }

    private fun stopListening() {
        endSession("已停止", playFarewell = false)
        capture?.stop()
        capture = null
        detector?.release()
        detector = null
        restoreAudioSettings()
        wakeLock?.runCatching { release() }
        wakeLock = null
        phase = VoiceState.Phase.IDLE
        VoiceState.set(VoiceState.Phase.IDLE, "未启动")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (capture != null) stopListening()
        super.onDestroy()
    }
}
