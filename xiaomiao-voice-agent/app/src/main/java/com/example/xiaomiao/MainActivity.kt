package com.example.xiaomiao

import android.animation.ValueAnimator
import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * 主页：只做状态展示，不承载任何语音操作。
 *
 * 布局：中间巨型 logo，下方状态灯（红=未启动 / 黄=接入中 / 绿=监听或对话）+ 阶段文字，
 * 再往下是一张三步使用指南卡（纯文字，不可点击），右上角齿轮进入 [SettingsActivity]。
 * 启动/停止监听、手动唤醒、KWS 自检、Key 配置全在设置页，
 * 这样主页可以长期开着而不担心误触，也方便当摆件放在桌面上。
 */
class MainActivity : AppCompatActivity() {
    private lateinit var statusDot: View
    private lateinit var statusLabel: TextView
    private var pulse: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 锁定竖屏：防止旋转重建界面。
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        Config.init(this)
        setContentView(R.layout.activity_main)

        statusDot = findViewById(R.id.statusDot)
        statusLabel = findViewById(R.id.statusLabel)

        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                VoiceState.flow.collect { applyStatus(it) }
            }
        }

        autoStartIfNeeded()
    }

    /**
     * 打开应用即开始监听：主页没有任何操作按钮，用户的自然预期是「打开就能用」。
     *
     * 以下任一情况都不自动拉起，留在未启动态并在设置页给出原因，避免一进来就报错：
     * - 用户在设置页关掉了「打开应用自动监听」；
     * - 服务已在运行（返回主页、从通知进入等，不需要重复启动）；
     * - 还没授予麦克风权限（服务会立刻失败）；
     * - 选了 SDK 路径但没保存 Key（服务层会拦下）。
     */
    private fun autoStartIfNeeded() {
        if (!Config.autoStartOnOpen) return
        if (VoiceState.flow.value.phase != VoiceState.Phase.IDLE) return
        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!micGranted) return
        if (!Config.useDemo && !Config.hasSavedApiKey) return
        ContextCompat.startForegroundService(
            this,
            Intent(this, WakeService::class.java).setAction(WakeService.ACTION_START),
        )
    }

    /** 把语音阶段映射成灯色 + 短语。 */
    private fun applyStatus(snapshot: VoiceState.Snapshot) {
        val colorRes: Int
        val labelRes: Int
        when (snapshot.phase) {
            VoiceState.Phase.IDLE -> {
                colorRes = R.color.state_idle_red
                labelRes = R.string.home_state_idle
            }
            VoiceState.Phase.LISTENING_WAKE -> {
                colorRes = R.color.state_running_green
                labelRes = R.string.home_state_listening
            }
            VoiceState.Phase.SESSION_CONNECTING -> {
                colorRes = R.color.state_transition_amber
                labelRes = R.string.home_state_connecting
            }
            VoiceState.Phase.SESSION_ACTIVE -> {
                colorRes = R.color.state_running_green
                labelRes = R.string.home_state_active
            }
        }
        statusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
        statusLabel.setText(labelRes)

        // 「怎么用」由常驻的三步指南承担，这里只维护灯色与呼吸，避免重复提示。

        // 对话中让灯呼吸，与「监听中常亮」区分，不额外加文案。
        if (snapshot.phase == VoiceState.Phase.SESSION_ACTIVE) startPulse() else stopPulse()
    }

    private fun startPulse() {
        if (pulse != null) return
        pulse = ValueAnimator.ofFloat(1f, 0.45f).apply {
            duration = 720L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { statusDot.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        statusDot.alpha = 1f
    }

    override fun onDestroy() {
        stopPulse()
        super.onDestroy()
    }
}
