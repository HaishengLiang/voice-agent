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
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject

class WakeService : Service() {
    companion object {
        const val ACTION_START = "com.example.xiaomiao.START"
        const val ACTION_STOP = "com.example.xiaomiao.STOP"
        const val ACTION_WAKE = "com.example.xiaomiao.WAKE"
        const val ACTION_END_SESSION = "com.example.xiaomiao.END_SESSION"
        const val ACTION_UPDATE_AUDIO = "com.example.xiaomiao.UPDATE_AUDIO"
        const val ACTION_RELOAD_KWS = "com.example.xiaomiao.RELOAD_KWS"

        const val CHANNEL_ID = "moss_wake_channel"
        private const val NOTIFICATION_ID = 1001

        private const val DIAG_TAG = "BufDiag"

        /** 单帧峰值绝对值，用来判断补灌帧是否被 AEC 削成静音。 */
        internal fun pcmPeak(frame: ShortArray): Int {
            var peak = 0
            for (s in frame) {
                val a = if (s < 0) -s.toInt() else s.toInt()
                if (a > peak) peak = a
            }
            return peak
        }

        /** 一组帧的 RMS，0 表示全静音。 */
        internal fun pcmRms(frames: List<ShortArray>): Int {
            var sum = 0.0
            var count = 0
            for (f in frames) for (s in f) {
                sum += s.toDouble() * s.toDouble()
                count++
            }
            return if (count == 0) 0 else kotlin.math.sqrt(sum / count).toInt()
        }

        /** 每帧峰值压成紧凑串，便于在 logcat 里看能量落在哪个时间段。 */
        internal fun peaksCompact(frames: List<ShortArray>): String =
            frames.joinToString(",") { pcmPeak(it).toString() }

        // 用户道别关键词：命中后主动结束会话、关闭 WebSocket、回到 KWS 唤醒监听
        private val FAREWELL_KEYWORDS = listOf(
            "退下", "再见", "拜拜", "先这样", "就这样吧", "结束对话", "挂了", "不聊了",
            "goodbye", "byebye", "bye", "seeyou",
        )

        /** 道别词判定：去空白/标点、转小写后做包含匹配。提到 companion 便于 JVM 单测。 */
        internal fun isFarewell(text: String): Boolean {
            if (text.isBlank()) return false
            val normalized = text.lowercase().replace(
                Regex("[\\s，。！？、,.!?~～；;：:\"'“”‘’—\\-]"), ""
            )
            if (normalized.isBlank()) return false
            return FAREWELL_KEYWORDS.any { normalized.contains(it) }
        }

        /** PCM16 ShortArray 转小端字节流（上行音频格式）。提到 companion 便于 JVM 单测。 */
        internal fun pcm16ToBytes(frame: ShortArray): ByteArray {
            val bytes = ByteArray(frame.size * 2)
            for (i in frame.indices) {
                val value = frame[i].toInt() and 0xFFFF
                bytes[i * 2] = (value and 0xFF).toByte()
                bytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
            }
            return bytes
        }

        /**
         * 通话音量锚定计算：按媒体音量比例算出目标通话音量，限幅最高 80%，永不顶满。
         * 从 alignCallVolumeToMedia 抽出纯计算部分，便于 JVM 单测（无需 AudioManager）。
         * 返回 null 表示无需调整（输入非法）。
         */
        internal fun computeCallVolume(
            mediaVol: Int, mediaMax: Int, callMin: Int, callMax: Int,
        ): Int? {
            if (mediaMax <= 0 || callMax <= callMin) return null
            val ratio = (mediaVol.toFloat() / mediaMax.toFloat()).coerceIn(0f, 1f)
            val capped = if (ratio <= 0f) 0.12f else ratio.coerceAtMost(0.8f)
            return (callMin + capped * (callMax - callMin)).toInt().coerceIn(callMin, callMax)
        }

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
    private var ringBuffer = AudioRingBuffer()
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

    // 环形缓存灌入状态：命中唤醒词时记下帧序号，会话就绪后把其后的帧补灌进上行。
    @Volatile private var wakeFrameSeq = -1L
    @Volatile private var flushedWakeSeq = -1L
    @Volatile private var pendingBufferedFlush = false
    @Volatile private var micOpened = false

    // 补灌诊断：记录各时间点的墙钟时间，便于在 logcat 里对齐时序。
    private var diagWakeAtMs = 0L
    private var diagSessionAtMs = 0L
    private var diagSuppressEndLogged = false
    private var diagLiveFrames = 0

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
            ACTION_RELOAD_KWS -> reloadDetector()
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
        // SDK 路径没有可用 Key 就不空跑监听：开机自启等入口会绕过界面校验，这里兜底。
        if (!Config.useDemo && Config.apiKey.isBlank()) {
            VoiceState.set(VoiceState.Phase.IDLE, "启动失败", getString(R.string.error_sdk_need_key))
            stopSelf()
            return
        }
        try {
            configureAudioForAec()
            detector = WakeDetector(applicationContext)
            ringBuffer.clear()
            wakeFrameSeq = -1L
            flushedWakeSeq = -1L
            capture = AudioCapture(
                ringBuffer = ringBuffer,
                onFrame = { frame, seq -> onAudioFrame(frame, seq) },
            )
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
     * 换词后重建检测器：词表文件已被设置页改写，这里释放旧 spotter 并新建，
     * 新 stream 从头累积识别上下文。音频采集（capture）保持不变，不打断监听。
     * 只在唤醒监听态重建；会话进行中不动，避免误伤正在进行的对话。
     */
    private fun reloadDetector() {
        if (phase != VoiceState.Phase.LISTENING_WAKE || capture == null) return
        try {
            detector?.release()
            detector = WakeDetector(applicationContext)
            wakeFrameSeq = -1L
            flushedWakeSeq = -1L
            VoiceState.set(VoiceState.Phase.LISTENING_WAKE, "唤醒词已更新，等待唤醒...")
            updateNotification("唤醒词已更新，监听中")
        } catch (e: Throwable) {
            VoiceState.set(
                error = "换词失败：${e.message ?: e.javaClass.simpleName}",
            )
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
        val target = computeCallVolume(mediaVol, mediaMax, callMin, callMax) ?: return
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

    private fun onAudioFrame(frame: ShortArray, seq: Long) {
        when (phase) {
            VoiceState.Phase.LISTENING_WAKE -> {
                try {
                    val detector = this.detector ?: return
                    detector.accept(frame)
                    val keyword = detector.poll()
                    if (keyword != null) {
                        // 记录命中帧序号：补灌只取该帧之后的语音，不含唤醒词本身。
                        wakeFrameSeq = seq
                        diagWakeAtMs = System.currentTimeMillis()
                        Log.d(DIAG_TAG, "WAKE_HIT seq=$seq at=$diagWakeAtMs latest=${ringBuffer.latestSeq()}")
                        mainHandler.post { startSession("命中唤醒词：$keyword") }
                    }
                } catch (e: Throwable) {
                    mainHandler.post {
                        VoiceState.set(error = "唤醒检测失败：${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
            VoiceState.Phase.SESSION_CONNECTING, VoiceState.Phase.SESSION_ACTIVE -> {
                val now = System.currentTimeMillis()
                // 本地问候音播放期间不上传，避免设备外放被麦克风采到当成用户输入。
                if (now < suppressMicUntilMs) return
                // 抑制刚解除的这一帧：记录它相对唤醒的时刻——这就是补灌窗口的右边界。
                if (!diagSuppressEndLogged) {
                    diagSuppressEndLogged = true
                    Log.d(
                        DIAG_TAG,
                        "SUPPRESS_END seq=$seq at=$now sinceWake=${now - diagWakeAtMs}ms " +
                            "suppressUntil=$suppressMicUntilMs micOpened=$micOpened pending=$pendingBufferedFlush",
                    )
                }
                // 会话刚就绪时先补灌缓存里「命中之后」的帧，灌完再走实时上行，保证顺序不倒挂。
                if (pendingBufferedFlush) flushBufferedFrames(seq)
                // 实时上行帧每秒打一次峰值，用来判断用户真正说话时是否有能量。
                diagLiveFrames++
                if (diagLiveFrames % 25 == 1) {
                    Log.d(DIAG_TAG, "LIVE_FRAME seq=$seq at=$now peak=${pcmPeak(frame)}")
                }
                val bytes = pcm16ToBytes(frame)
                client?.sendBytes(bytes)
            }
            else -> Unit
        }
    }

    /**
     * 把环形缓存里唤醒命中之后的帧一次性灌入上行。
     * 只灌一次（flushedWakeSeq 去重），且灌到当前帧之前，避免与紧随其后的实时帧重复或倒序。
     */
    private fun flushBufferedFrames(currentSeq: Long) {
        if (!pendingBufferedFlush || wakeFrameSeq < 0) {
            pendingBufferedFlush = false
            return
        }
        pendingBufferedFlush = false
        flushedWakeSeq = wakeFrameSeq
        val frames = ringBuffer.range(wakeFrameSeq, currentSeq - 1)
        Log.d(
            DIAG_TAG,
            "FLUSH wakeSeq=$wakeFrameSeq curSeq=$currentSeq count=${frames.size} " +
                "rms=${pcmRms(frames)} at=${System.currentTimeMillis()} " +
                "sinceWake=${System.currentTimeMillis() - diagWakeAtMs}ms " +
                "peaks=[${peaksCompact(frames)}]",
        )
        if (frames.isEmpty()) return
        client?.sendBufferedFrames(frames.map { pcm16ToBytes(it) })
        updateNotification("已补入唤醒后的语音（${frames.size} 帧）")
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
        // 新一轮会话：准备补灌唤醒后的缓存语音（手动唤醒时 wakeFrameSeq 为 -1，自动跳过）。
        pendingBufferedFlush = wakeFrameSeq >= 0
        flushedWakeSeq = -1L
        micOpened = false
        diagSessionAtMs = System.currentTimeMillis()
        diagSuppressEndLogged = false
        diagLiveFrames = 0
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
        Log.d(
            DIAG_TAG,
            "SESSION_START at=$diagSessionAtMs sinceWake=${diagSessionAtMs - diagWakeAtMs}ms " +
                "pending=$pendingBufferedFlush wakeSeq=$wakeFrameSeq " +
                "greetingMs=$greetingMs suppressUntil=$suppressMicUntilMs",
        )

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

    /**
     * 打开麦克风并进入对话态。可能被多个配置回执（session.updated / voice_changed）
     * 或 session_attached 触发，用 micOpened 保证一轮会话只执行一次。
     */
    private fun openMicAndFlush() {
        if (micOpened) return
        if (phase != VoiceState.Phase.SESSION_CONNECTING) return
        micOpened = true
        val micAt = System.currentTimeMillis()
        client?.sendJson("mic_state", "enabled" to true)
        phase = VoiceState.Phase.SESSION_ACTIVE
        Log.d(
            DIAG_TAG,
            "MIC_OPEN at=$micAt sinceWake=${micAt - diagWakeAtMs}ms " +
                "suppressUntil=$suppressMicUntilMs pending=$pendingBufferedFlush",
        )
        VoiceState.set(VoiceState.Phase.SESSION_ACTIVE, "对话中，可直接说话或打断")
        updateNotification("MOSS-Live 对话中")
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
                // 非 demo 模式在开启麦克风前下发自定义人设/指令与对话音色。
                // 按顺序发送：session.update → change_voice → mic_state，
                // 等 session.updated / voice_changed 回执确认后再开 mic、补灌缓存。
                if (!Config.useDemo) {
                    val persona = Config.persona
                    val instructions = Config.instructions
                    val dialogueVoice = Config.dialogueVoice
                    if (persona.isNotBlank() || instructions.isNotBlank()) {
                        client?.sendSessionConfig(persona, instructions)
                    }
                    if (dialogueVoice.isNotBlank()) {
                        client?.sendChangeVoice(dialogueVoice)
                    }
                    // 没有自定义配置需要确认时，直接开麦。
                    if (persona.isBlank() && instructions.isBlank() && dialogueVoice.isBlank()) {
                        openMicAndFlush()
                    }
                } else {
                    openMicAndFlush()
                }
            }
            "session.updated" -> {
                val error = data.optString("error")
                if (error.isNotBlank()) VoiceState.set(error = "会话更新失败：$error")
                // session.update 的回执到达后才开麦，确保人设/指令先生效。
                if (phase == VoiceState.Phase.SESSION_CONNECTING && !Config.useDemo) {
                    openMicAndFlush()
                }
            }
            "voice_changed" -> {
                val error = data.optString("error")
                if (error.isNotBlank()) VoiceState.set(error = "音色切换失败：$error")
                if (phase == VoiceState.Phase.SESSION_CONNECTING && !Config.useDemo) {
                    openMicAndFlush()
                }
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
                // 先取样已播放时长：flush 会重置播放头，之后再读就是 0。
                val playedMs = player?.stopAndClear() ?: 0L
                pendingChunks = 0
                audioDonePending = false
                client?.sendJson(
                    "playback.stopped",
                    "run_id" to runId,
                    "turn_id" to turnId,
                    "played_audio_ms" to playedMs,
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
