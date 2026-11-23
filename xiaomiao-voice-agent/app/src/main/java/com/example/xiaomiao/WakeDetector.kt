package com.example.xiaomiao

import android.content.Context
import java.io.File
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * sherpa-onnx 离线关键词检测。模型与 keywords.txt 放在 assets/kws/ 下。
 */
class WakeDetector(private val context: Context) {
    companion object {
        private const val SAMPLE_RATE = 16_000

        /**
         * 从 wav 字节中提取 PCM16 数据（跳过 RIFF 头，定位 data 块）。
         * 提到 companion 作为 internal 纯函数，便于 JVM 单测（原生 spotter 无法在 JVM 加载）。
         */
        internal fun wavPcm(all: ByteArray): ByteArray? {
            var dataPos = -1
            var i = 12
            while (i + 8 <= all.size) {
                val id = String(all, i, 4, Charsets.US_ASCII)
                if (id == "data") { dataPos = i + 8; break }
                val size = (all[i + 4].toInt() and 0xFF) or ((all[i + 5].toInt() and 0xFF) shl 8) or
                    ((all[i + 6].toInt() and 0xFF) shl 16) or ((all[i + 7].toInt() and 0xFF) shl 24)
                i += 8 + size + (size and 1)
            }
            return if (dataPos in 0..all.size) all.copyOfRange(dataPos, all.size) else null
        }
    }

    private val spotter: KeywordSpotter
    private val stream: OnlineStream

    init {
        // 模型已从 assets 落到 filesDir（见 KwsFiles），用文件系统路径加载。
        // 关键：KeywordSpotter 不传 assetManager 时走 newFromFile，词表才可被运行时改写。
        val modelDir = KwsFiles.ensureReady(context)
        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = File(modelDir, "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx").absolutePath,
                decoder = File(modelDir, "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx").absolutePath,
                joiner = File(modelDir, "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx").absolutePath,
            ),
            tokens = File(modelDir, "tokens.txt").absolutePath,
            modelType = "zipformer2",
        )

        spotter = KeywordSpotter(
            config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = modelConfig,
                keywordsFile = File(modelDir, "keywords.txt").absolutePath,
                // keywords.txt 里有多条同义发音（标准音 + 方言把「小」读四声的变体），
                // 它们共享前缀，活跃路径给少了第二条会被第一条挤掉：实测 4 条只能中 39/47，
                // 给到 8 条才能 47/47。RTF 只从 0.021 涨到 0.029，代价可以忽略。
                maxActivePaths = 8,
                // 与离线验证（scripts/tune-kws.py）一致：boost 1.0 + 阈值 0.35 时
                // 正样本 47/47、21 条负样本零误唤醒；boost 提到 1.5 以上会开始误唤醒「小妙小妙」。
                keywordsScore = 1.0f,
                keywordsThreshold = 0.35f,
            ),
        )
        stream = spotter.createStream()
    }

    fun accept(samples: ShortArray) {
        val floats = FloatArray(samples.size) { samples[it] / 32768f }
        stream.acceptWaveform(floats, SAMPLE_RATE)
    }

    /**
     * 返回命中的关键词；未命中返回 null。
     * 关键：只要 isReady 就持续 decode 累积识别上下文，只有确实命中后才 reset。
     * 不能每帧 decode 后无条件 reset，否则模型永远看不到完整唤醒词。
     */
    fun poll(): String? {
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val keyword = spotter.getResult(stream).keyword
            if (!keyword.isBlank()) {
                spotter.reset(stream)
                return keyword
            }
        }
        return null
    }

    /**
     * 自检：用唤醒词样本 wav 跑一遍检测，返回命中词；未命中返回原因说明。
     * 样本优先用 filesDir/wake_test.wav（用户为自定义词录制），否则回退 assets 内的
     * 默认「小喵小喵」录音——换词后若没录新样本，自检会未命中，属预期，文案里会标注。
     * 用独立 stream，不影响正在监听的 stream。
     */
    fun selfTest(): String {
        return try {
            val customSample = File(context.filesDir, "wake_test.wav")
            val useCustom = customSample.exists() && customSample.length() > 0
            val sampleLabel = if (useCustom) "自定义样本" else "默认样本（小喵小喵）"
            val inputStream = if (useCustom) customSample.inputStream()
                              else context.assets.open("greeting/xiaomiao_test.wav")
            val bytes: ByteArray
            try {
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (true) {
                    val n = inputStream.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                bytes = out.toByteArray()
            } finally {
                inputStream.close()
            }
            val pcm = wavPcm(bytes) ?: return "wav 解析失败"
            val testStream = spotter.createStream()
            val floats = FloatArray(pcm.size / 2) { i ->
                ((pcm[i * 2 + 1].toInt() shl 8) or (pcm[i * 2].toInt() and 0xFF)).toShort() / 32768f
            }
            testStream.acceptWaveform(floats, SAMPLE_RATE)
            val tail = FloatArray((0.66f * SAMPLE_RATE).toInt())
            testStream.acceptWaveform(tail, SAMPLE_RATE)
            testStream.inputFinished()
            var hit = ""
            var loops = 0
            while (spotter.isReady(testStream) && loops < 10000) {
                spotter.decode(testStream)
                loops++
                val kw = spotter.getResult(testStream).keyword
                if (kw.isNotBlank()) { hit = kw; break }
            }
            runCatching { testStream.release() }
            if (hit.isNotBlank()) "命中：$hit（$sampleLabel，解码 $loops 次）"
            else "未命中（$sampleLabel，解码 $loops 次）${if (!useCustom) "；换词后请用新样本或录音验证" else ""}"
        } catch (e: Throwable) {
            "自检异常：${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun release() {
        runCatching { stream.release() }
        runCatching { spotter.release() }
    }
}
