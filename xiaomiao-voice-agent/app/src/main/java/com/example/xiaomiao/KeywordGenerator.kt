package com.example.xiaomiao

import android.content.Context

/**
 * 运行时把中文唤醒词转成 sherpa-onnx ppinyin 音素序列。
 *
 * AAR 里没有 text2token/pinyin 能力，所以走「构建期生成映射表 + 运行时查表」：
 * assets/kws/pinyin_map.txt 由 scripts/build-pinyin-map.py 生成（pypinyin + tokens.txt），
 * 这里只做逐字查表拼接，口径与官方 sherpa-onnx-cli text2token 一致。
 *
 * 变体策略沿用唤醒词调优实测结论（docs/kws-wake-tuning.md）：
 * 方言把三声读四声时音素路径不同、调阈值无效，必须写并行变体行。
 * 因此对每个含三声韵母的字生成一行「该字整体变四声」，让 sherpa 多路径去匹配。
 * 行数上限与 maxActivePaths=8 对齐——变体过多会挤占路径并抬升误唤醒。
 */
object KeywordGenerator {
    const val BOOST = 1.0f
    const val THRESHOLD = 0.35f
    const val MAX_LINES = 8

    private const val MAP_ASSET = "kws/pinyin_map.txt"

    /** 韵母里的三声字符 -> 四声字符（Unicode 码点固定，可直接做字符替换）。 */
    private val TONE3_TO_4 = mapOf(
        'ǎ' to 'à', 'ě' to 'è', 'ǐ' to 'ì', 'ǒ' to 'ò', 'ǔ' to 'ù', 'ǚ' to 'ǜ',
    )

    @Volatile
    private var pinyinMap: Map<Char, String>? = null

    /** 加载映射表到内存（约 2 万字、225 KB），只需一次，线程安全。 */
    @Synchronized
    fun load(context: Context) {
        if (pinyinMap != null) return
        context.assets.open(MAP_ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
            pinyinMap = parseMap(lines)
        }
    }

    /**
     * 解析映射表文本为字->音素表。抽成纯函数供 JVM 单测复用（不依赖 Android Context）。
     * 行格式：汉字=读音1 token 序列|读音2 token 序列
     */
    internal fun parseMap(lines: Sequence<String>): Map<Char, String> {
        val map = HashMap<Char, String>(21_000)
        lines.forEach { line ->
            val i = line.indexOf('=')
            if (i > 0) map[line[0]] = line.substring(i + 1)
        }
        return map
    }

    /** 仅供单元测试注入内存映射表，绕过 assets 加载。 */
    internal fun injectMapForTest(map: Map<Char, String>) {
        pinyinMap = map
    }

    data class Generated(
        val word: String,
        /** 主读音音素，展示给用户确认用。 */
        val phonetics: String,
        /** 完整 keywords 文件内容，可直接写盘。 */
        val keywordsText: String,
        val lineCount: Int,
        /** pinyin_map.txt 未覆盖的字（生僻字），这些字会导致对应路径无效。 */
        val missing: List<Char>,
    )

    /** 输入中文唤醒词，生成 keywords 文件内容。未 load() 时抛异常。 */
    fun generate(word: String): Generated {
        val map = pinyinMap ?: error("KeywordGenerator 尚未初始化")
        val clean = word.trim()
        require(clean.isNotEmpty()) { "唤醒词不能为空" }
        require(clean.length <= 12) { "唤醒词最多 12 个字" }

        val chars = clean.toList()
        val missing = chars.filter { it !in map }
        require(missing.isEmpty()) {
            "这些字不在音素表里：${missing.joinToString("")}（生僻字或非常用读音）"
        }

        // 每个字取第一个读音（pypinyin heteronym[0] 即常用音）。
        val tokensByChar: List<List<String>> = chars.map { map.getValue(it).split('|')[0].split(' ') }

        fun format(tokens: List<String>) =
            "${tokens.joinToString(" ")} :$BOOST #$THRESHOLD @$clean"

        val lines = mutableListOf(format(tokensByChar.flatten()))
        val mainPhonetics = tokensByChar.flatten().joinToString(" ")

        // 三声变四声：按「不同的字」去重，一个字出现多次时整行一起变
        // （与现有「小喵小喵 -> x iào m iāo x iào m iāo」两行配置完全一致）。
        val tone3Chars = chars.distinct().filter { ch ->
            val idx = chars.indexOf(ch)
            tokensByChar[idx].any { token -> token.any { it in TONE3_TO_4 } }
        }
        for (ch in tone3Chars) {
            if (lines.size >= MAX_LINES) break
            val variantTokens = mutableListOf<String>()
            chars.zip(tokensByChar).forEach { (c, toks) ->
                toks.forEach { token ->
                    variantTokens += if (c == ch) {
                        token.map { TONE3_TO_4[it] ?: it }.joinToString("")
                    } else {
                        token
                    }
                }
            }
            lines += format(variantTokens)
        }

        return Generated(
            word = clean,
            phonetics = mainPhonetics,
            keywordsText = lines.joinToString("\n", postfix = "\n"),
            lineCount = lines.size,
            missing = emptyList(),
        )
    }
}
