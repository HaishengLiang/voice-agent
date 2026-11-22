package com.example.xiaomiao

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * MOSS-Live Realtime WebSocket 客户端。
 * 上行：16 kHz mono PCM S16LE 二进制帧；下行：48 kHz mono PCM S16LE 二进制帧。
 */
class MossRealtimeClient(
    private val endpoint: String,
    private val apiKey: String,
    private val onTextEvent: (String, JSONObject) -> Unit,
    private val onBinaryAudio: (ByteArray) -> Unit,
    private val onConnectionState: (String) -> Unit,
    private val useDemo: Boolean = false,
) {
    data class AudioMeta(
        val runId: String,
        val turnId: String,
        val chunkId: String?,
    )

    private val httpClient = OkHttpClient.Builder().build()
    private var socket: WebSocket? = null
    private val pendingAudioMeta = ConcurrentLinkedQueue<AudioMeta>()

    @Volatile
    var connected: Boolean = false
        private set

    fun connect() {
        close()
        connected = false
        val requestBuilder = Request.Builder().url(endpoint)
        // demo 端（官网）不需要也不接受 Authorization 头。
        if (!useDemo) requestBuilder.header("Authorization", "Bearer $apiKey")
        val request = requestBuilder.build()

        socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                onConnectionState("connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = try {
                    JSONObject(text)
                } catch (_: Exception) {
                    return
                }
                val action = obj.optString("action")
                val data = obj.optJSONObject("data") ?: JSONObject()
                if (action == "response.audio.context") {
                    pendingAudioMeta.add(
                        AudioMeta(
                            runId = data.optString("run_id"),
                            turnId = data.optString("turn_id"),
                            chunkId = data.optString("chunk_id").ifBlank { null },
                        )
                    )
                }
                onTextEvent(action, data)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onBinaryAudio(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                onConnectionState("error: ${t.message ?: t.javaClass.simpleName}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                onConnectionState("closed ($code)")
            }
        })
    }

    fun pollAudioMeta(): AudioMeta? = pendingAudioMeta.poll()

    fun sendBytes(data: ByteArray) {
        socket?.send(ByteString.of(*data))
    }

    fun sendJson(action: String, vararg fields: Pair<String, Any?>) {
        val obj = JSONObject().put("action", action)
        fields.forEach { (key, value) ->
            obj.put(key, value ?: JSONObject.NULL)
        }
        socket?.send(obj.toString())
    }

    fun close() {
        socket?.close(1000, "client done")
        socket = null
        connected = false
        pendingAudioMeta.clear()
    }
}
