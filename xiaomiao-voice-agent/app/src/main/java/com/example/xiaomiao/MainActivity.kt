package com.example.xiaomiao

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var apiKeyInput: EditText
    private lateinit var apiKeyLabel: TextView
    private lateinit var demoSwitch: CheckBox
    private lateinit var audioOutputGroup: RadioGroup
    private lateinit var idleTimeoutInput: EditText
    private lateinit var stateText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var rootScrollView: ScrollView
    private lateinit var batteryStatusText: TextView
    private lateinit var ignoreBatteryButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var wakeButton: Button
    private var lastMessageCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 锁定竖屏：防止旋转导致界面重建、服务状态显示被重置。
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        Config.init(this)
        setContentView(R.layout.activity_main)

        apiKeyInput = findViewById(R.id.apiKeyInput)
        apiKeyLabel = findViewById(R.id.apiKeyLabel)
        demoSwitch = findViewById(R.id.demoSwitch)
        audioOutputGroup = findViewById(R.id.audioOutputGroup)
        idleTimeoutInput = findViewById(R.id.idleTimeoutInput)
        stateText = findViewById(R.id.stateText)
        transcriptText = findViewById(R.id.transcriptText)
        rootScrollView = findViewById(R.id.rootScrollView)
        batteryStatusText = findViewById(R.id.batteryStatusText)
        ignoreBatteryButton = findViewById(R.id.ignoreBatteryButton)
        ignoreBatteryButton.setOnClickListener { requestIgnoreBatteryOptimization() }
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        wakeButton = findViewById(R.id.wakeButton)

        demoSwitch.isChecked = Config.useDemo
        audioOutputGroup.check(
            if (Config.audioOutput == AudioOutput.SPEAKER) R.id.radioSpeaker else R.id.radioHeadset
        )
        apiKeyInput.setText(Config.apiKey)
        idleTimeoutInput.setText(Config.idleTimeoutMs.toString())
        applyModeVisibility()

        demoSwitch.setOnCheckedChangeListener { _, _ -> applyModeVisibility() }
        audioOutputGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == -1) return@setOnCheckedChangeListener
            Config.audioOutput = if (checkedId == R.id.radioSpeaker) AudioOutput.SPEAKER else AudioOutput.HEADSET
            // 服务已在运行时立即应用，无需重启监听；未运行则下次启动时读取配置即可。
            if (VoiceState.flow.value.phase != VoiceState.Phase.IDLE) {
                startService(WakeService.ACTION_UPDATE_AUDIO)
            }
        }

        startButton.setOnClickListener {
            saveConfig()
            startService(WakeService.ACTION_START)
        }
        stopButton.setOnClickListener {
            startService(WakeService.ACTION_STOP)
        }
        wakeButton.setOnClickListener {
            saveConfig()
            val phase = VoiceState.flow.value.phase
            val inSession = phase == VoiceState.Phase.SESSION_CONNECTING ||
                phase == VoiceState.Phase.SESSION_ACTIVE
            // 会话中点击 = 结束对话（保持监听）；否则 = 手动唤醒发起对话。
            startService(if (inSession) WakeService.ACTION_END_SESSION else WakeService.ACTION_WAKE)
        }
        findViewById<Button>(R.id.selftestButton).setOnClickListener {
            stateText.text = "阶段：KWS 自检中..."
            lifecycleScope.launch {
                val result = withContext(Dispatchers.Default) {
                    runCatching {
                        val detector = WakeDetector(applicationContext)
                        val r = detector.selfTest()
                        detector.release()
                        r
                    }.getOrElse { "自检异常：${it.message}" }
                }
                stateText.text = "阶段：KWS 自检\n$result"
            }
        }

        requestPermissionsIfNeeded()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                VoiceState.flow.collect { snapshot ->
                    // 按钮按运行状态切换：未启动只能「启动监听」，已启动则只能「停止监听」。
                    val isIdle = snapshot.phase == VoiceState.Phase.IDLE
                    startButton.visibility = if (isIdle) View.VISIBLE else View.GONE
                    stopButton.visibility = if (isIdle) View.GONE else View.VISIBLE

                    // 手动唤醒按钮在会话中变为「结束对话」，可随时手动结束、回到唤醒监听。
                    val inSession = snapshot.phase == VoiceState.Phase.SESSION_CONNECTING ||
                        snapshot.phase == VoiceState.Phase.SESSION_ACTIVE
                    wakeButton.text = getString(
                        if (inSession) R.string.btn_end_session else R.string.btn_wake
                    )

                    stateText.text = buildString {
                        append("阶段：").append(snapshot.phase.name).append('\n')
                        append(snapshot.status)
                        snapshot.error?.let { append("\n错误：").append(it) }
                    }
                    // 对话以列表形式累积展示，每条消息独立成段，不再被新一轮覆写。
                    transcriptText.text = if (snapshot.messages.isEmpty()) {
                        getString(R.string.transcript_empty)
                    } else {
                        buildString {
                            snapshot.messages.forEachIndexed { index, msg ->
                                if (index > 0) append("\n\n")
                                append(if (msg.role == VoiceState.Role.USER) "我：" else "MOSS：")
                                append(msg.text)
                                if (msg.streaming) append(" …")
                            }
                        }
                    }
                    // 仅在新消息条数增加时滚到底部：流式刷新同一条时不打扰用户回看历史。
                    if (snapshot.messages.size > lastMessageCount) {
                        rootScrollView.post { rootScrollView.fullScroll(View.FOCUS_DOWN) }
                    }
                    lastMessageCount = snapshot.messages.size
                }
            }
        }
    }

    private fun startService(action: String) {
        val intent = Intent(this, WakeService::class.java).setAction(action)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun applyModeVisibility() {
        val isDemo = demoSwitch.isChecked
        apiKeyLabel.visibility = if (isDemo) View.GONE else View.VISIBLE
        apiKeyInput.visibility = if (isDemo) View.GONE else View.VISIBLE
    }

    private fun saveConfig() {
        Config.useDemo = demoSwitch.isChecked
        Config.apiKey = apiKeyInput.text?.toString()?.trim().orEmpty()
        val timeout = idleTimeoutInput.text?.toString()?.toLongOrNull() ?: 30_000L
        Config.idleTimeoutMs = timeout.coerceIn(5_000L, 600_000L)
    }

    /** 回到前台时刷新电池优化状态（用户可能刚在系统设置里改过）。 */
    override fun onResume() {
        super.onResume()
        refreshBatteryStatus()
    }

    private fun refreshBatteryStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            batteryStatusText.text = getString(R.string.battery_unknown)
            ignoreBatteryButton.visibility = View.GONE
            return
        }
        val pm = getSystemService(PowerManager::class.java)
        val ignored = pm?.isIgnoringBatteryOptimizations(packageName) == true
        batteryStatusText.text = getString(
            if (ignored) R.string.battery_ignored else R.string.battery_optimized
        )
        // 已在白名单里就不用再点；未加入时显示入口。
        ignoreBatteryButton.visibility = if (ignored) View.GONE else View.VISIBLE
    }

    /**
     * 引导用户把本应用加入电池优化白名单。
     * 优先直接弹系统授权对话框；部分 ROM 不支持时降级到应用详情页让用户手动设置。
     */
    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        val handled = runCatching { startActivity(intent) }.isSuccess
        if (!handled) {
            val detail = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            runCatching { startActivity(detail) }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1001)
    }
}
