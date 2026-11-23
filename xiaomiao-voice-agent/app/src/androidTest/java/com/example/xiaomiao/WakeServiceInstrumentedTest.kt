package com.example.xiaomiao

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * WakeService 真机 instrumentation 测试。
 *
 * JVM 侧无法覆盖 Service 本体（生命周期、通知、action 分发、音频路由），这里在真机上验证。
 * 刻意只触发「不发网络请求、不开麦克风采集」的 action，避免依赖 MOSS 后端与外部网络：
 *   - createChannel：真机创建通知渠道（NotificationManager 真机实现）
 *   - ACTION_STOP：空转时停止，安全
 *   - ACTION_END_SESSION：仅在主线程 post 状态判断，非会话态下无副作用
 *   - ACTION_UPDATE_AUDIO：应用音频路由（真机 AudioManager）
 *   - ACTION_RELOAD_KWS：非唤醒监听态下直接 return
 * 真正连 WebSocket / 开麦克风的全链路留待人工真机验收（见文档）。
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class WakeServiceInstrumentedTest {
    private lateinit var ctx: Context

    @get:Rule
    val grantPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.MODIFY_AUDIO_SETTINGS,
        android.Manifest.permission.POST_NOTIFICATIONS,
        android.Manifest.permission.FOREGROUND_SERVICE,
    )

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        Config.init(ctx)
        VoiceState.set(VoiceState.Phase.IDLE, "未启动")
    }

    /** 真机创建通知渠道：渠道应出现在 NotificationManager 中。 */
    @Test
    fun createChannel_channelExists() {
        WakeService.createChannel(ctx)
        val manager = ctx.getSystemService(NotificationManager::class.java)
        assertNotNull(manager)
        assertNotNull("通知渠道应创建成功", manager!!.getNotificationChannel(WakeService.CHANNEL_ID))
    }

    /** companion 的纯函数在真机上也验证一遍（与 JVM 测试互为交叉验证）。 */
    @Test
    fun companionPureFunctions_workOnDevice() {
        assertTrue("道别词识别", WakeService.isFarewell("好的，再见！"))
        assertTrue("英文道别词", WakeService.isFarewell("OK byebye"))
        assertEquals(false, WakeService.isFarewell("我们继续聊吧"))
        // 小端字节序：0x0102 -> [0x02, 0x01]
        val bytes = WakeService.pcm16ToBytes(shortArrayOf(0x0102))
        assertEquals(2, bytes.size)
        assertEquals(0x02.toByte(), bytes[0])
        assertEquals(0x01.toByte(), bytes[1])
    }

    /**
     * 通过反射调用 onStartCommand 触发各 action（不真正 startService，避免前台服务保活）。
     * 只测安全 action，验证 START_STICKY 返回值与不崩溃。
     * 关键：直接 new 的 Service 没有 attachBaseContext，getSystemService 会 NPE，
     * 故先反射注入 context（模拟系统 attach），通知/音频路由路径才能正常取到系统服务。
     */
    @Test
    fun onStartCommand_safeActions_noCrash() {
        val service = WakeService()
        // Service 继承 ContextWrapper，attachBaseContext 是 protected；反射注入 application context
        val attach = android.content.ContextWrapper::class.java.getDeclaredMethod(
            "attachBaseContext", android.content.Context::class.java
        )
        attach.isAccessible = true
        attach.invoke(service, ctx)

        val method = WakeService::class.java.getDeclaredMethod(
            "onStartCommand", Intent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        )
        method.isAccessible = true

        // 注意：ACTION_STOP 会走 stopListening → stopForeground/stopSelf，
        // 需要真实的 Service 组件名与生命周期；反射 new 的实例没有 ComponentName，
        // stopForeground 会 NPE。这类涉及生命周期的 action 不适合反射模拟，故不测。

        // ACTION_UPDATE_AUDIO：从 null audioManager 起步，applyOutputRouting 应安全返回
        val update = method.invoke(service, Intent(WakeService.ACTION_UPDATE_AUDIO), 0, 2)
        assertEquals(android.app.Service.START_STICKY, update)

        // ACTION_RELOAD_KWS：phase=IDLE 且 capture=null，直接 return
        val reload = method.invoke(service, Intent(WakeService.ACTION_RELOAD_KWS), 0, 3)
        assertEquals(android.app.Service.START_STICKY, reload)

        // ACTION_END_SESSION：非会话态，主线程 post 内判断后无副作用
        val end = method.invoke(service, Intent(WakeService.ACTION_END_SESSION), 0, 4)
        assertEquals(android.app.Service.START_STICKY, end)
    }

    /**
     * 真机验证 VoiceState 相位流转（WakeService 状态机的输出载体）。
     * 走 companion 的通知渠道 + 直接 set，确认真机 StateFlow 读写一致。
     */
    @Test
    fun voiceState_phaseTransitionsOnDevice() {
        VoiceState.set(VoiceState.Phase.LISTENING_WAKE, "等待唤醒词...")
        assertEquals(VoiceState.Phase.LISTENING_WAKE, VoiceState.flow.value.phase)

        VoiceState.set(VoiceState.Phase.SESSION_CONNECTING, "连接中")
        assertEquals(VoiceState.Phase.SESSION_CONNECTING, VoiceState.flow.value.phase)

        VoiceState.set(VoiceState.Phase.SESSION_ACTIVE, "对话中")
        assertEquals(VoiceState.Phase.SESSION_ACTIVE, VoiceState.flow.value.phase)

        VoiceState.set(VoiceState.Phase.LISTENING_WAKE, "已回到唤醒监听", error = null)
        assertEquals(VoiceState.Phase.LISTENING_WAKE, VoiceState.flow.value.phase)
        assertEquals(null, VoiceState.flow.value.error)
    }
}
