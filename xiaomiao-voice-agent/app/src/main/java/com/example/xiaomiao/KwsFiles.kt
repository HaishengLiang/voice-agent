package com.example.xiaomiao

import android.content.Context
import java.io.File

/**
 * KWS 模型的本地副本管理。
 *
 * sherpa-onnx Android AAR 的 KeywordSpotter 有两条加载路径：
 *   传 AssetManager  -> newFromAsset，只能读 assets（只读，无法换词）
 *   不传            -> newFromFile，从文件系统读（可写，支持运行时换词）
 * 所以首次启动把 assets/kws 的模型复制到 filesDir，之后 WakeDetector 走文件系统加载，
 * 换词只需改写 filesDir 里的 keywords.txt 并重建 detector，不用重装 APK。
 */
object KwsFiles {
    private const val ASSET_DIR = "kws"
    // 模型或映射表有更新时 +1，触发全量重新复制（App 升级场景）。
    private const val MODEL_VERSION = 1
    private const val VERSION_FILE = "kws.version"

    private val MODEL_FILES = listOf(
        "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        "tokens.txt",
        "pinyin_map.txt",
    )
    private const val KEYWORDS_FILE = "keywords.txt"
    private const val DEFAULT_KEYWORDS = "x iǎo m iāo x iǎo m iāo :1.0 #0.35 @小喵小喵\nx iào m iāo x iào m iāo :1.0 #0.35 @小喵小喵\n"

    /** filesDir/kws 目录，模型已就绪。线程安全，可重复调用。 */
    @Synchronized
    fun ensureReady(context: Context): File {
        val dir = File(context.filesDir, ASSET_DIR)
        val versionFile = File(context.filesDir, VERSION_FILE)
        val versionOk = versionFile.readTextOrNull() == MODEL_VERSION.toString()
        val filesOk = MODEL_FILES.all { File(dir, it).exists() && File(dir, it).length() > 0 }

        if (!versionOk || !filesOk) {
            dir.mkdirs()
            MODEL_FILES.forEach { name ->
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    File(dir, name).outputStream().use { input.copyTo(it) }
                }
            }
            // 默认词表只在首次（或版本升级）写入，不覆盖用户已保存的自定义词表。
            val kwFile = File(dir, KEYWORDS_FILE)
            if (!kwFile.exists()) kwFile.writeText(DEFAULT_KEYWORDS, Charsets.UTF_8)
            versionFile.writeText(MODEL_VERSION.toString(), Charsets.UTF_8)
        }
        return dir
    }

    fun keywordsFile(context: Context): File = File(ensureReady(context), KEYWORDS_FILE)

    /** 写入用户自定义词表；writeText 先截断再写，避免残留旧行。 */
    fun writeKeywords(context: Context, text: String): File {
        val file = keywordsFile(context)
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    /** 恢复默认唤醒词。 */
    fun resetKeywords(context: Context): File = writeKeywords(context, DEFAULT_KEYWORDS)

    fun hasCustomKeywords(context: Context): Boolean {
        val text = keywordsFile(context).readTextOrNull() ?: return false
        // readTextOrNull 做了 trim，DEFAULT_KEYWORDS 末尾带 \n，比较时必须同口径
        return text != DEFAULT_KEYWORDS.trim()
    }

    private fun File.readTextOrNull(): String? = try {
        if (exists()) readText(Charsets.UTF_8).trim() else null
    } catch (_: Exception) {
        null
    }
}
