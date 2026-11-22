package com.example.xiaomiao

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object VoiceState {
    enum class Phase { IDLE, LISTENING_WAKE, SESSION_CONNECTING, SESSION_ACTIVE }

    /** 对话角色：用户说话 / MOSS 回复。 */
    enum class Role { USER, MODEL }

    /**
     * 一条对话消息。
     * @param streaming true 表示这一轮还在流式转写中（后续片段会继续写入），false 表示已定稿。
     */
    data class Message(
        val role: Role,
        val text: String,
        val streaming: Boolean = false,
    )

    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        val status: String = "未启动",
        val messages: List<Message> = emptyList(),
        val error: String? = null,
    )

    private val mutable = MutableStateFlow(Snapshot())
    val flow: StateFlow<Snapshot> = mutable.asStateFlow()

    fun set(phase: Phase? = null, status: String? = null, error: String? = null) {
        val current = mutable.value
        mutable.value = current.copy(
            phase = phase ?: current.phase,
            status = status ?: current.status,
            error = error,
        )
    }

    /**
     * 追加或更新一条转录消息，保留多轮对话历史。
     *
     * 一轮对话会先来多个 updated（流式片段，done=false），最后来一个 done（done=true）。
     * - 同一角色的连续片段更新「当前正在流式」的那一条；
     * - 角色切换、或上一条已定稿、或用户打断（MOSS 仍在流式时开口）时，先把旧流式消息收尾，
     *   再新增一条，从而不会互相覆盖。
     */
    fun appendTranscript(role: Role, text: String, done: Boolean) {
        val list = mutable.value.messages.toMutableList()
        val activeIndex = list.indexOfLast { it.streaming }
        val active = if (activeIndex >= 0) list[activeIndex] else null

        if (active != null && active.role == role) {
            // 同一角色继续流式：更新当前这条消息
            list[activeIndex] = active.copy(text = text, streaming = !done)
        } else {
            // 上一条流式消息先收尾，再新增一条
            if (active != null) list[activeIndex] = active.copy(streaming = false)
            list.add(Message(role, text, streaming = !done))
        }
        mutable.value = mutable.value.copy(messages = list)
    }

    /** 用户转写。updated 传 done=false；done 事件传 done=true。 */
    fun setUserText(text: String, done: Boolean = false) = appendTranscript(Role.USER, text, done)

    /** MOSS 转写。updated 传 done=false；done 事件传 done=true。 */
    fun setModelText(text: String, done: Boolean = false) = appendTranscript(Role.MODEL, text, done)

    fun clearTranscript() {
        mutable.value = mutable.value.copy(messages = emptyList())
    }
}
