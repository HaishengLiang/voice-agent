package com.example.xiaomiao

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * MOSS TTS 客户端：POST /v1/audio/speech，把一段文本合成为 wav 并存到 app 内部 filesDir。
 *
 * 文档：https://platform.moss.cn/docs/reference/speech
 * - endpoint：https://api.mosi.cn/v1/audio/speech
 * - 鉴权：Authorization: Bearer <API Key>
 * - 必填字段：model / input / voice_id
 * - response_format=wav：返回标准 wav 二进制（带 RIFF 头），GreetingPlayer 可直接读取。
 *
 * 仅在 API Key 模式可用；demo 模式音色固定、提示音走自带 assets，不会调用本类。
 */
class TtsClient(private val context: Context) {
    companion object {
        private const val ENDPOINT = "https://api.mosi.cn/v1/audio/speech"
        private const val MODEL = "moss-tts-2.0-flash-preview"
        private const val TIMEOUT_SEC = 30L
        // 中文每字约 0.2 秒的轻快语速（实测原速约 0.4 秒/字，停顿长、听感拖）。
        // expected_duration_sec 是「期望总时长」，模型会据此压缩语速与停顿，
        // 比本地变速自然；不传则走模型默认慢速。
        private const val SEC_PER_HANZI = 0.20
    }

    /**
     * 估算轻快语速下的目标时长：按汉字数算，显式 [pause Ns] 标记额外累加。
     * 实测：4 字「在呢老板」原速 1.60s → 目标 0.8s；6 字「好的我退下了」→ 1.2s。
     */
    private fun estimateDurationSec(text: String): Double {
        val hanzi = text.count { it in '\u4e00'..'\u9fff' }
        val pauseSec = Regex("""\[pause\s+([\d.]+)s\]""").findAll(text)
            .sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
        return (hanzi * SEC_PER_HANZI + pauseSec).coerceAtLeast(0.5)
    }

    private val http = OkHttpClient.Builder()
        .callTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    /**
     * 合成文本并保存到本地文件。成功返回保存后的文件名，失败抛异常（含服务端错误信息）。
     * 用 IO 调度器调用：网络请求 + 文件写入都在后台。
     */
    suspend fun synthesize(text: String, voiceId: String, targetFile: String): String =
        withContext(Dispatchers.IO) {
            require(text.isNotBlank()) { "提示音文本不能为空" }
            require(voiceId.isNotBlank()) { "请先填写 TTS 音色编码（voice_id）" }

            val body = JSONObject()
                .put("model", MODEL)
                .put("input", text)
                .put("voice_id", voiceId)
                // 指定中文，避免模型按标点盲目拉长停顿。
                .put("language", "Chinese")
                // 目标时长按字数估算，等效提高语速、压缩停顿；flash 模型支持。
                .put("expected_duration_sec", estimateDurationSec(text))
                // 非流式默认直接返回音频二进制（wav 带 RIFF 头），无需指定 delivery_method。
                .put("response_format", "wav")
                .toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer ${Config.apiKey}")
                .post(body)
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = runCatching { response.body?.string() }.getOrNull().orEmpty()
                    val msg = runCatching {
                        JSONObject(err).optJSONObject("error")?.optString("message")
                    }.getOrNull().takeUnless { it.isNullOrBlank() } ?: err.take(200)
                    throw IOException("TTS 失败（HTTP ${response.code}）：${msg ?: "未知错误"}")
                }
                val bytes = response.body?.bytes()
                    ?: throw IOException("TTS 返回空音频")
                if (bytes.size < 44) throw IOException("TTS 返回的音频数据异常")
                context.openFileOutput(targetFile, Context.MODE_PRIVATE).use { it.write(bytes) }
                targetFile
            }
        }
}
