package com.example.xiaomiao

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * MOSS 公开音色列表客户端（网页端音色库同款接口，免 API Key）。
 *
 * GET https://platform.moss.cn/portal-api/audio/voices/public?limit=150[&cursor=xxx]
 * - 返回 voice_id / voice_name / gender / age_range / accent / tags / preview_audio_url 等。
 * - preview_audio_url 是签名 URL（OSS，有效期见 display_assets.expires_at），每次拉取都会刷新，
 *   不要长期缓存 URL；试听时直接用当次返回的链接。
 * - 该接口无需鉴权，demo / SDK 模式都能浏览和试听。
 *
 * 注意：账号私有克隆音色走 api.mosi.cn/v1/audio/voices，只有 id/name，不在此公开列表内。
 */
class VoiceListClient {
    data class Voice(
        val id: String,
        val name: String,
        val language: String,
        val gender: String,
        val ageRange: String,
        val accent: String,
        val tags: List<String>,
        val previewUrl: String,
        val sampleUrl: String,
    )

    data class Page(val voices: List<Voice>, val nextCursor: String?, val hasMore: Boolean)

    companion object {
        private const val ENDPOINT = "https://platform.moss.cn/portal-api/audio/voices/public"
        private const val PAGE_SIZE = 100
        private const val TIMEOUT_SEC = 25L
    }

    private val http = OkHttpClient.Builder()
        .callTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    /** 拉取一页音色。cursor 为 null 取第一页。 */
    suspend fun fetchPage(cursor: String? = null): Page = withContext(Dispatchers.IO) {
        val url = buildString {
            append(ENDPOINT).append("?limit=").append(PAGE_SIZE)
            if (!cursor.isNullOrBlank()) append("&cursor=").append(cursor)
        }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0")
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("音色列表请求失败（HTTP ${response.code}）")
            }
            val body = response.body?.string().orEmpty()
            val root = runCatching { JSONObject(body) }
                .getOrElse { throw IOException("音色列表返回格式异常") }

            val arr = root.optJSONArray("data")
                ?: throw IOException(root.optString("error") ?: "音色列表为空")
            val list = mutableListOf<Voice>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val preview = o.optString("preview_audio_url", "")
                if (preview.isBlank()) continue // 没有试听链接的跳过，避免点了没反应
                val id = o.optString("voice_id").ifBlank { o.optString("id") }
                if (id.isBlank()) continue
                val tagArr = o.optJSONArray("tags")
                val tags = mutableListOf<String>()
                if (tagArr != null) {
                    for (j in 0 until tagArr.length()) tags += tagArr.optString(j)
                }
                list += Voice(
                    id = id,
                    name = o.optString("voice_name").ifBlank { o.optString("name", "未命名") },
                    language = o.optString("language", ""),
                    gender = o.optString("gender", ""),
                    ageRange = o.optString("age_range", ""),
                    accent = o.optString("accent", ""),
                    tags = tags,
                    previewUrl = preview,
                    sampleUrl = o.optString("audio_sample_url", ""),
                )
            }
            val next = root.optString("next_cursor", "").ifBlank { null }
            Page(list, next, root.optBoolean("has_more", false))
        }
    }
}
