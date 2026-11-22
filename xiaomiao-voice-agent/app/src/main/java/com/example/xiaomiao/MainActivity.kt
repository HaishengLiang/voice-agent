package com.example.xiaomiao

import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.ActivityInfo
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
 * 右上角齿轮进入 [SettingsActivity]。启动/停止监听、手动唤醒、KWS 自检、Key 配置全在设置页，
 * 这样主页可以长期开着而不担心误触，也方便当摆件放在桌面上。
 */
class MainActivity : AppCompatActivity() {
    private lateinit var statusDot: View
    private lateinit var statusLabel: TextView
    private lateinit var homeHint: TextView
    private var pulse: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 锁定竖屏：防止旋转重建界面。
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        Config.init(this)
        setContentView(R.layout.activity_main)

        statusDot = findViewById(R.id.statusDot)
        statusLabel = findViewById(R.id.statusLabel)
        homeHint = findViewById(R.id.homeHint)

        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                VoiceState.flow.collect { applyStatus(it) }
            }
        }
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

        // 未启动时给一条去设置页的引导；运行中收起，保持首页干净。
        homeHint.visibility =
            if (snapshot.phase == VoiceState.Phase.IDLE) View.VISIBLE else View.GONE

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
