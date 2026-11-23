package com.example.xiaomiao

import com.example.xiaomiao.VoiceState.Phase
import com.example.xiaomiao.VoiceState.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * VoiceState 单元测试（纯 JVM）。
 * 验证状态快照更新与多轮转录消息的流式追加/收尾逻辑——这块错了对话会串台或丢字。
 */
class VoiceStateTest {
    @Before
    fun setUp() {
        VoiceState.clearTranscript()
        VoiceState.set(phase = Phase.IDLE, status = "未启动", error = null)
    }

    @Test
    fun `set 只更新传入字段并保留其余`() {
        VoiceState.set(phase = Phase.LISTENING_WAKE, status = "聆听中")
        assertEquals(Phase.LISTENING_WAKE, VoiceState.flow.value.phase)
        assertEquals("聆听中", VoiceState.flow.value.status)
        // error 传 null 显式清空
        VoiceState.set(error = "出错了")
        assertEquals("出错了", VoiceState.flow.value.error)
        VoiceState.set(status = "恢复")
        assertEquals(Phase.LISTENING_WAKE, VoiceState.flow.value.phase) // phase 保留
        assertNull(VoiceState.flow.value.error) // 未传 error 则清空
    }

    @Test
    fun `同角色连续流式片段更新同一条消息`() {
        VoiceState.setUserText("你", false)
        VoiceState.setUserText("你好", false)
        VoiceState.setUserText("你好吗", true)
        val msgs = VoiceState.flow.value.messages
        assertEquals(1, msgs.size)
        assertEquals("你好吗", msgs[0].text)
        assertEquals(Role.USER, msgs[0].role)
        assertEquals(false, msgs[0].streaming) // done=true 收尾
    }

    @Test
    fun `角色切换时旧流式消息收尾再新增`() {
        VoiceState.setUserText("用户说的", false) // 流式
        VoiceState.setModelText("MOSS 回复", false)
        val msgs = VoiceState.flow.value.messages
        assertEquals(2, msgs.size)
        assertEquals(false, msgs[0].streaming) // 用户那条被收尾
        assertEquals(Role.MODEL, msgs[1].role)
        assertEquals(true, msgs[1].streaming)
    }

    @Test
    fun `多轮对话保留历史 不互相覆盖`() {
        VoiceState.setUserText("第一轮", true)
        VoiceState.setModelText("回复一", true)
        VoiceState.setUserText("第二轮", true)
        VoiceState.setModelText("回复二", true)
        val msgs = VoiceState.flow.value.messages
        assertEquals(4, msgs.size)
        assertEquals(listOf("第一轮", "回复一", "第二轮", "回复二"), msgs.map { it.text })
        assertEquals(listOf(Role.USER, Role.MODEL, Role.USER, Role.MODEL), msgs.map { it.role })
    }

    @Test
    fun `MOSS 流式时用户打断 旧消息收尾再开新条`() {
        VoiceState.setModelText("MOSS 正在说", false) // MODEL 流式
        VoiceState.setUserText("打断一下", false)     // 用户打断
        val msgs = VoiceState.flow.value.messages
        assertEquals(2, msgs.size)
        assertEquals(Role.MODEL, msgs[0].role)
        assertEquals(false, msgs[0].streaming)
        assertEquals(Role.USER, msgs[1].role)
    }

    @Test
    fun `clearTranscript 清空所有消息`() {
        VoiceState.setUserText("你好", true)
        assertEquals(1, VoiceState.flow.value.messages.size)
        VoiceState.clearTranscript()
        assertEquals(0, VoiceState.flow.value.messages.size)
    }
}
