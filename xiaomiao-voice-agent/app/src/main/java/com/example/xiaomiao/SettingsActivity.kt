package com.example.xiaomiao

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    // 基本设置
    private lateinit var audioOutputGroup: RadioGroup
    private lateinit var idleTimeoutInput: EditText
    private lateinit var stateText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var rootScrollView: ScrollView
    private lateinit var batteryStatusText: TextView
    private lateinit var ignoreBatteryButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var wakeButton: Button
    private lateinit var wakeWordInput: EditText
    private lateinit var wakeWordStatus: TextView

    // Agent
    private lateinit var apiKeyInput: EditText
    private lateinit var apiKeySection: LinearLayout
    private lateinit var apiKeyStatusText: TextView
    private lateinit var saveKeyButton: Button
    private lateinit var modeGroup: RadioGroup
    private lateinit var modeHintText: TextView
    private lateinit var agentCustomSection: LinearLayout
    private lateinit var agentDemoHint: TextView
    private lateinit var personaInput: EditText
    private lateinit var instructionsInput: EditText
    private lateinit var dialogueVoiceInput: EditText
    private lateinit var agentStatusText: TextView

    // 提示音
    private lateinit var toneCustomSection: LinearLayout
    private lateinit var toneDemoHint: TextView
    private lateinit var greetingTextInput: EditText
    private lateinit var farewellTextInput: EditText
    private lateinit var toneStatusText: TextView

    // 音色库
    private lateinit var voiceCurrentText: TextView
    private lateinit var voiceListStatus: TextView
    private lateinit var voiceListContainer: LinearLayout
    private lateinit var voiceLoadMoreButton: Button
    private lateinit var voiceListClient: VoiceListClient
    private lateinit var voicePreviewPlayer: VoicePreviewPlayer
    private val voices = mutableListOf<VoiceListClient.Voice>()
    private var voiceCursor: String? = null
    private var voiceHasMore = false
    private var voiceLoading = false
    private var selectedVoiceId: String = ""

    private lateinit var ttsClient: TtsClient
    private var lastMessageCount = 0
    /** 初始化setText会触发 doAfterTextChanged，用它避免误删已生成文件。 */
    private var initializingToneFields = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        Config.init(this)
        setContentView(R.layout.activity_settings)
        KeywordGenerator.load(applicationContext)
        ttsClient = TtsClient(this)
        voiceListClient = VoiceListClient()
        voicePreviewPlayer = VoicePreviewPlayer()

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        setupTabs()

        // ---- 基本设置 ----
        audioOutputGroup = findViewById(R.id.audioOutputGroup)
        idleTimeoutInput = findViewById(R.id.idleTimeoutInput)
        stateText = findViewById(R.id.stateText)
        transcriptText = findViewById(R.id.transcriptText)
        rootScrollView = findViewById(R.id.pageBasic)
        batteryStatusText = findViewById(R.id.batteryStatusText)
        ignoreBatteryButton = findViewById(R.id.ignoreBatteryButton)
        ignoreBatteryButton.setOnClickListener { requestIgnoreBatteryOptimization() }
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        wakeButton = findViewById(R.id.wakeButton)

        audioOutputGroup.check(
            if (Config.audioOutput == AudioOutput.SPEAKER) R.id.radioSpeaker else R.id.radioHeadset
        )
        idleTimeoutInput.setText(Config.idleTimeoutMs.toString())

        val autoStartSwitch = findViewById<CheckBox>(R.id.autoStartSwitch)
        autoStartSwitch.isChecked = Config.autoStartOnOpen
        autoStartSwitch.setOnCheckedChangeListener { _, checked -> Config.autoStartOnOpen = checked }

        audioOutputGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == -1) return@setOnCheckedChangeListener
            Config.audioOutput = if (checkedId == R.id.radioSpeaker) AudioOutput.SPEAKER else AudioOutput.HEADSET
            if (VoiceState.flow.value.phase != VoiceState.Phase.IDLE) {
                startService(WakeService.ACTION_UPDATE_AUDIO)
            }
        }

        startButton.setOnClickListener {
            if (!prepareConfig()) return@setOnClickListener
            startService(WakeService.ACTION_START)
        }
        stopButton.setOnClickListener { startService(WakeService.ACTION_STOP) }
        wakeButton.setOnClickListener {
            if (!prepareConfig()) return@setOnClickListener
            val phase = VoiceState.flow.value.phase
            val inSession = phase == VoiceState.Phase.SESSION_CONNECTING ||
                phase == VoiceState.Phase.SESSION_ACTIVE
            startService(if (inSession) WakeService.ACTION_END_SESSION else WakeService.ACTION_WAKE)
        }
        findViewById<Button>(R.id.selftestButton).setOnClickListener {
            stateText.text = "阶段：KWS 自检中..."
            lifecycleScope.launch {
                val result = withContext(Dispatchers.Default) {
                    runCatching {
                        val detector = WakeDetector(applicationContext)
                        val r = detector.selfTest()
                        detector.release()
                        r
                    }.getOrElse { "自检异常：${it.message}" }
                }
                stateText.text = "阶段：KWS 自检\n$result"
            }
        }

        // ---- 唤醒词（运行时可改）----
        wakeWordInput = findViewById(R.id.wakeWordInput)
        wakeWordStatus = findViewById(R.id.wakeWordStatus)
        wakeWordInput.setText(Config.wakeWord)
        refreshWakeWordStatus()
        findViewById<Button>(R.id.saveWakeWordButton).setOnClickListener { applyWakeWord() }
        findViewById<Button>(R.id.resetWakeWordButton).setOnClickListener {
            wakeWordInput.setText(Config.DEFAULT_WAKE_WORD)
            applyWakeWord()
        }

        // ---- Agent ----
        apiKeyInput = findViewById(R.id.apiKeyInput)
        apiKeySection = findViewById(R.id.apiKeySection)
        apiKeyStatusText = findViewById(R.id.apiKeyStatusText)
        saveKeyButton = findViewById(R.id.saveKeyButton)
        modeGroup = findViewById(R.id.modeGroup)
        modeHintText = findViewById(R.id.modeHintText)
        agentCustomSection = findViewById(R.id.agentCustomSection)
        agentDemoHint = findViewById(R.id.agentDemoHint)
        personaInput = findViewById(R.id.personaInput)
        instructionsInput = findViewById(R.id.instructionsInput)
        dialogueVoiceInput = findViewById(R.id.dialogueVoiceInput)
        agentStatusText = findViewById(R.id.agentStatusText)

        modeGroup.check(if (Config.useDemo) R.id.radioDemo else R.id.radioSdk)
        apiKeyInput.setText(Config.apiKey)
        personaInput.setText(Config.persona)
        instructionsInput.setText(Config.instructions)
        dialogueVoiceInput.setText(Config.dialogueVoice)

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == -1) return@setOnCheckedChangeListener
            val demo = isDemoMode()
            Config.useDemo = demo
            applyModeVisibility()
            refreshApiKeyStatus()
            val phase = VoiceState.flow.value.phase
            if (phase == VoiceState.Phase.SESSION_CONNECTING ||
                phase == VoiceState.Phase.SESSION_ACTIVE
            ) {
                startService(WakeService.ACTION_END_SESSION)
            }
        }
        apiKeyInput.doAfterTextChanged { refreshApiKeyStatus() }
        saveKeyButton.setOnClickListener {
            val key = apiKeyInput.text?.toString()?.trim().orEmpty()
            if (key.isBlank()) {
                Toast.makeText(this, R.string.toast_key_empty, Toast.LENGTH_SHORT).show()
                apiKeyInput.requestFocus()
                return@setOnClickListener
            }
            Config.apiKey = key
            apiKeyInput.setText(key)
            refreshApiKeyStatus()
            Toast.makeText(this, R.string.toast_key_saved, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.saveAgentButton).setOnClickListener {
            Config.persona = personaInput.text?.toString()?.trim().orEmpty()
            Config.instructions = instructionsInput.text?.toString()?.trim().orEmpty()
            Config.dialogueVoice = dialogueVoiceInput.text?.toString()?.trim().orEmpty()
            agentStatusText.text = getString(R.string.agent_saved)
            agentStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            Toast.makeText(this, R.string.agent_saved, Toast.LENGTH_SHORT).show()
        }

        // ---- 提示音 ----
        toneCustomSection = findViewById(R.id.toneCustomSection)
        toneDemoHint = findViewById(R.id.toneDemoHint)
        greetingTextInput = findViewById(R.id.greetingTextInput)
        farewellTextInput = findViewById(R.id.farewellTextInput)
        toneStatusText = findViewById(R.id.toneStatusText)

        // ---- 音色库 ----
        voiceCurrentText = findViewById(R.id.voiceCurrentText)
        voiceListStatus = findViewById(R.id.voiceListStatus)
        voiceListContainer = findViewById(R.id.voiceListContainer)
        voiceLoadMoreButton = findViewById(R.id.voiceLoadMoreButton)
        selectedVoiceId = Config.ttsVoice
        refreshVoiceCurrent()
        findViewById<Button>(R.id.voiceReloadButton).setOnClickListener { loadVoices(reset = true) }
        voiceLoadMoreButton.setOnClickListener { loadVoices(reset = false) }
        findViewById<Button>(R.id.voiceResetButton).setOnClickListener { resetToneDefaults() }
        loadVoices(reset = true)

        greetingTextInput.setText(Config.greetingText)
        farewellTextInput.setText(Config.farewellText)
        initializingToneFields = false

        // 文本改动即落盘，便于生成时读取；改动文本后原生成文件失效，提示重新生成。
        greetingTextInput.doAfterTextChanged {
            if (initializingToneFields) return@doAfterTextChanged
            Config.greetingText = it?.toString()?.trim().orEmpty()
            invalidateToneFile(Config.GREETING_FILE, "联通音")
        }
        farewellTextInput.doAfterTextChanged {
            if (initializingToneFields) return@doAfterTextChanged
            Config.farewellText = it?.toString()?.trim().orEmpty()
            invalidateToneFile(Config.FAREWELL_FILE, "挂断音")
        }

        findViewById<Button>(R.id.genGreetingButton).setOnClickListener {
            generateTone(
                text = Config.greetingText,
                file = Config.GREETING_FILE,
                label = "联通音",
            )
        }
        findViewById<Button>(R.id.genFarewellButton).setOnClickListener {
            generateTone(
                text = Config.farewellText,
                file = Config.FAREWELL_FILE,
                label = "挂断音",
            )
        }
        findViewById<Button>(R.id.playGreetingButton).setOnClickListener {
            playLocalOrAsset(Config.GREETING_FILE, "联通音")
        }
        findViewById<Button>(R.id.playFarewellButton).setOnClickListener {
            playLocalOrAsset(Config.FAREWELL_FILE, "挂断音")
        }

        applyModeVisibility()
        refreshApiKeyStatus()
        refreshToneStatus()

        requestPermissionsIfNeeded()
        collectVoiceState()
    }

    override fun onDestroy() {
        super.onDestroy()
        voicePreviewPlayer.stop()
    }

    // ---------------- 音色库 ----------------
    private fun refreshVoiceCurrent() {
        val name = voices.firstOrNull { it.id == selectedVoiceId }?.name
        val label = if (selectedVoiceId == Config.DEFAULT_TTS_VOICE) {
            "${Config.DEFAULT_VOICE_NAME}（默认）"
        } else name
        voiceCurrentText.text = getString(
            R.string.voice_current_label,
            if (label.isNullOrBlank()) selectedVoiceId.ifBlank { "未选择" } else "$label · $selectedVoiceId"
        )
    }

    /** 拉取音色列表；reset=true 从头加载，false 追加下一页。 */
    private fun loadVoices(reset: Boolean) {
        if (voiceLoading) return
        if (!reset && !voiceHasMore) {
            voiceListStatus.text = "已加载全部音色"
            return
        }
        voiceLoading = true
        voiceListStatus.text = getString(R.string.voice_list_loading)
        voiceLoadMoreButton.isEnabled = false
        lifecycleScope.launch {
            val result = runCatching {
                voiceListClient.fetchPage(if (reset) null else voiceCursor)
            }
            result.onSuccess { page ->
                if (reset) {
                    voices.clear()
                    voiceListContainer.removeAllViews()
                }
                voices.addAll(page.voices)
                page.voices.forEach { voice ->
                    voiceListContainer.addView(buildVoiceRow(voice))
                }
                voiceCursor = page.nextCursor
                voiceHasMore = page.hasMore && page.voices.isNotEmpty()
                voiceListStatus.text = if (voices.isEmpty()) {
                    getString(R.string.voice_list_empty)
                } else {
                    "已加载 ${voices.size} 个音色"
                }
                voiceLoadMoreButton.visibility = if (voiceHasMore) View.VISIBLE else View.GONE
                refreshVoiceCurrent()
            }.onFailure { e ->
                voiceListStatus.text = getString(R.string.voice_list_fail, e.message ?: "未知错误")
                voiceLoadMoreButton.visibility = if (voices.isEmpty()) View.GONE else View.VISIBLE
            }
            voiceLoading = false
            voiceLoadMoreButton.isEnabled = true
        }
    }

    /** 构建单个音色行：整行点击选用，试听按钮单独点击播放官方试听音频。 */
    private fun buildVoiceRow(voice: VoiceListClient.Voice): View {
        val row = layoutInflater.inflate(R.layout.item_voice, voiceListContainer, false)
        row.findViewById<TextView>(R.id.voiceNameText).text = voice.name
        val meta = buildString {
            if (voice.language.isNotBlank()) append(formatLang(voice.language))
            if (voice.gender.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(if (voice.gender == "female") "女" else if (voice.gender == "male") "男" else voice.gender)
            }
            if (voice.ageRange.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(formatAge(voice.ageRange))
            }
            if (voice.accent.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(formatAccent(voice.accent))
            }
        }
        row.findViewById<TextView>(R.id.voiceMetaText).text = meta
        row.findViewById<TextView>(R.id.voiceTagText).text = voice.tags.take(4).joinToString(" · ")
        row.findViewById<TextView>(R.id.voiceIdText).text = voice.id

        // 选用
        row.setOnClickListener {
            Config.ttsVoice = voice.id
            selectedVoiceId = voice.id
            refreshVoiceCurrent()
            Toast.makeText(this, getString(R.string.voice_selected, voice.name), Toast.LENGTH_SHORT).show()
            // 换音色后已生成文件失效，提示重新生成
            invalidateGeneratedTones("音色已更换，请重新生成")
        }
        // 试听（官方试听链接，不走生成）
        row.findViewById<Button>(R.id.voicePreviewButton).setOnClickListener {
            previewVoice(voice)
        }
        return row
    }

    private fun previewVoice(voice: VoiceListClient.Voice) {
        voiceListStatus.text = getString(R.string.voice_previewing, voice.name)
        lifecycleScope.launch {
            runCatching { voicePreviewPlayer.play(voice.previewUrl) }
                .onSuccess { voiceListStatus.text = "试听完成：${voice.name}" }
                .onFailure { e ->
                    voiceListStatus.text = getString(R.string.voice_preview_fail, e.message ?: "未知错误")
                }
        }
    }

    /** 恢复默认配置：暖暖音色 + 两句默认提示音文本，并清除已生成文件。 */
    private fun resetToneDefaults() {
        voicePreviewPlayer.stop()
        Config.ttsVoice = Config.DEFAULT_TTS_VOICE
        Config.greetingText = Config.DEFAULT_GREETING_TEXT
        Config.farewellText = Config.DEFAULT_FAREWELL_TEXT
        selectedVoiceId = Config.DEFAULT_TTS_VOICE
        initializingToneFields = true
        greetingTextInput.setText(Config.greetingText)
        farewellTextInput.setText(Config.farewellText)
        initializingToneFields = false
        Config.deleteToneFile(this, Config.GREETING_FILE)
        Config.deleteToneFile(this, Config.FAREWELL_FILE)
        refreshVoiceCurrent()
        refreshToneStatus()
        toneStatusText.text = getString(R.string.voice_reset_done)
        Toast.makeText(this, R.string.voice_reset_done, Toast.LENGTH_SHORT).show()
    }

    private fun invalidateGeneratedTones(hint: String) {
        val g = Config.hasToneFile(this, Config.GREETING_FILE)
        val f = Config.hasToneFile(this, Config.FAREWELL_FILE)
        if (g || f) {
            if (g) Config.deleteToneFile(this, Config.GREETING_FILE)
            if (f) Config.deleteToneFile(this, Config.FAREWELL_FILE)
            toneStatusText.text = hint
            refreshToneStatus()
        }
    }

    private fun formatLang(v: String) = when (v) {
        "zh-CN" -> "中文·普通话"
        "en-US" -> "英语"
        else -> v
    }

    private fun formatAge(v: String) = when (v) {
        "juvenile" -> "少女"
        "young" -> "青年"
        "middle" -> "中年"
        "elderly" -> "老年"
        else -> v
    }

    private fun formatAccent(v: String) = when (v) {
        "standard-mandarin" -> "标准普通话"
        else -> v
    }

    // ---------------- 状态监听 ----------------
    private fun collectVoiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                VoiceState.flow.collect { snapshot ->
                    val isIdle = snapshot.phase == VoiceState.Phase.IDLE
                    startButton.visibility = if (isIdle) View.VISIBLE else View.GONE
                    stopButton.visibility = if (isIdle) View.GONE else View.VISIBLE

                    val inSession = snapshot.phase == VoiceState.Phase.SESSION_CONNECTING ||
                        snapshot.phase == VoiceState.Phase.SESSION_ACTIVE
                    wakeButton.text = getString(
                        if (inSession) R.string.btn_end_session else R.string.btn_wake
                    )

                    stateText.text = buildString {
                        append("阶段：").append(snapshot.phase.name).append('\n')
                        append(snapshot.status)
                        snapshot.error?.let { append("\n错误：").append(it) }
                    }
                    transcriptText.text = if (snapshot.messages.isEmpty()) {
                        getString(R.string.transcript_empty)
                    } else {
                        buildString {
                            snapshot.messages.forEachIndexed { index, msg ->
                                if (index > 0) append("\n\n")
                                append(if (msg.role == VoiceState.Role.USER) "我：" else "MOSS：")
                                append(msg.text)
                                if (msg.streaming) append(" …")
                            }
                        }
                    }
                    if (snapshot.messages.size > lastMessageCount) {
                        rootScrollView.post { rootScrollView.fullScroll(View.FOCUS_DOWN) }
                    }
                    lastMessageCount = snapshot.messages.size
                }
            }
        }
    }

    // ---------------- 唤醒词 ----------------
    /** 显示当前生效的唤醒词与音素路径，便于用户确认转换结果。 */
    private fun refreshWakeWordStatus() {
        val word = Config.wakeWord
        wakeWordStatus.text = try {
            val gen = runCatching { KeywordGenerator.generate(word) }.getOrNull()
            if (gen != null) {
                getString(R.string.wake_word_current, word) + "\n" +
                    getString(R.string.wake_word_phonetics, gen.phonetics, gen.lineCount)
            } else {
                getString(R.string.wake_word_current, word)
            }
        } catch (_: Exception) {
            getString(R.string.wake_word_current, word)
        }
    }

    /** 生成词表 -> 落盘 -> 通知服务重建 detector。生成失败时不改写任何状态。 */
    private fun applyWakeWord() {
        val word = wakeWordInput.text?.toString()?.trim().orEmpty()
        if (word.isEmpty()) {
            Toast.makeText(this, R.string.wake_word_empty, Toast.LENGTH_SHORT).show()
            wakeWordInput.requestFocus()
            return
        }
        val gen = try {
            KeywordGenerator.generate(word)
        } catch (e: Exception) {
            wakeWordStatus.text = "转换失败：${e.message ?: e.javaClass.simpleName}"
            wakeWordStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            return
        }
        KwsFiles.writeKeywords(applicationContext, gen.keywordsText)
        Config.wakeWord = word
        refreshWakeWordStatus()
        wakeWordStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))

        // 服务在监听时重建 detector；不在监听则下次启动时自然读新词表。
        val phase = VoiceState.flow.value.phase
        if (phase == VoiceState.Phase.LISTENING_WAKE) {
            startService(WakeService.ACTION_RELOAD_KWS)
        }
        Toast.makeText(this, getString(R.string.toast_wake_saved, word), Toast.LENGTH_SHORT).show()
    }

    // ---------------- Tab 切换 ----------------
    private fun setupTabs() {
        val tabs = listOf(
            findViewById<TextView>(R.id.tabBasic),
            findViewById<TextView>(R.id.tabAgent),
            findViewById<TextView>(R.id.tabTone),
        )
        val pages = listOf(
            findViewById<View>(R.id.pageBasic),
            findViewById<View>(R.id.pageAgent),
            findViewById<View>(R.id.pageTone),
        )
        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener {
                tabs.forEachIndexed { i, t ->
                    t.setBackgroundResource(
                        if (i == index) R.drawable.tab_selected else R.drawable.tab_unselected
                    )
                    t.setTextColor(
                        ContextCompat.getColor(
                            this,
                            if (i == index) android.R.color.white else android.R.color.darker_gray,
                        )
                    )
                    t.setTypeface(null, if (i == index) Typeface.BOLD else Typeface.NORMAL)
                    pages[i].visibility = if (i == index) View.VISIBLE else View.GONE
                }
            }
        }
    }

    // ---------------- 模式与可见性 ----------------
    private fun isDemoMode() = modeGroup.checkedRadioButtonId == R.id.radioDemo

    /** demo 模式隐藏 SDK 专属区域，禁用人设/指令/音色与提示音自定义。 */
    private fun applyModeVisibility() {
        val demo = isDemoMode()
        apiKeySection.visibility = if (demo) View.GONE else View.VISIBLE
        modeHintText.setText(if (demo) R.string.hint_mode_demo else R.string.hint_mode_sdk)

        agentCustomSection.visibility = if (demo) View.GONE else View.VISIBLE
        agentDemoHint.visibility = if (demo) View.VISIBLE else View.GONE

        toneCustomSection.visibility = if (demo) View.GONE else View.VISIBLE
        toneDemoHint.visibility = if (demo) View.VISIBLE else View.GONE

        val fields = listOf(personaInput, instructionsInput, dialogueVoiceInput)
        fields.forEach {
            it.isEnabled = !demo
            it.isFocusable = !demo
            it.isFocusableInTouchMode = !demo
        }
        findViewById<Button>(R.id.saveAgentButton).isEnabled = !demo
        findViewById<Button>(R.id.genGreetingButton).isEnabled = !demo
        findViewById<Button>(R.id.genFarewellButton).isEnabled = !demo
    }

    private fun refreshApiKeyStatus() {
        if (isDemoMode()) return
        val typed = apiKeyInput.text?.toString()?.trim().orEmpty()
        val saved = Config.apiKey
        val ready: Boolean
        val text: String
        when {
            Config.hasSavedApiKey && typed == saved -> {
                ready = true
                text = getString(R.string.api_key_saved, maskKey(saved))
            }
            typed.isNotBlank() -> {
                ready = false
                text = getString(R.string.api_key_unsaved)
            }
            saved.isNotBlank() -> {
                ready = false
                text = getString(R.string.api_key_builtin)
            }
            else -> {
                ready = false
                text = getString(R.string.api_key_missing)
            }
        }
        apiKeyStatusText.text = text
        apiKeyStatusText.setTextColor(
            ContextCompat.getColor(
                this,
                if (ready) android.R.color.holo_green_dark else android.R.color.holo_red_dark,
            )
        )
    }

    private fun maskKey(key: String): String =
        if (key.length <= 8) "****" else key.take(4) + "****" + key.takeLast(4)

    /** 提示音状态：已生成 / 未生成 / demo 固定。 */
    private fun refreshToneStatus() {
        if (isDemoMode()) {
            toneStatusText.text = "Demo 模式：使用自带提示音"
            return
        }
        val g = Config.hasToneFile(this, Config.GREETING_FILE)
        val f = Config.hasToneFile(this, Config.FAREWELL_FILE)
        toneStatusText.text = buildString {
            append("联通音：").append(if (g) "已生成" else "未生成")
            append("　挂断音：").append(if (f) "已生成" else "未生成")
        }
    }

    /** 文本改动后删除旧的生成文件，避免播放过期音频。 */
    private fun invalidateToneFile(file: String, label: String) {
        if (Config.hasToneFile(this, file)) {
            Config.deleteToneFile(this, file)
            toneStatusText.text = "$label 文本已修改，请重新生成"
        }
    }

    // ---------------- TTS 生成 / 试听 ----------------
    private fun generateTone(text: String, file: String, label: String) {
        if (isDemoMode() || !Config.hasSavedApiKey) {
            toneStatusText.text = getString(R.string.tone_need_key)
            return
        }
        if (Config.ttsVoice.isBlank()) {
            toneStatusText.text = getString(R.string.tone_need_voice)
            return
        }
        if (text.isBlank()) {
            toneStatusText.text = getString(R.string.tone_need_text)
            return
        }
        toneStatusText.text = "正在生成$label…"
        lifecycleScope.launch {
            val result = runCatching {
                ttsClient.synthesize(text, Config.ttsVoice, file)
            }
            result.onSuccess {
                toneStatusText.text = getString(R.string.tone_gen_ok, label)
                toneStatusText.setTextColor(ContextCompat.getColor(this@SettingsActivity, android.R.color.holo_green_dark))
                refreshToneStatus()
            }.onFailure { e ->
                toneStatusText.text = getString(R.string.tone_gen_fail, e.message ?: "未知错误")
                toneStatusText.setTextColor(ContextCompat.getColor(this@SettingsActivity, android.R.color.holo_red_dark))
            }
        }
    }

    /** 试听：优先用本地生成文件，否则播自带 assets（demo 或尚未生成）。 */
    private fun playLocalOrAsset(file: String, label: String) {
        val exists = !isDemoMode() && Config.hasToneFile(this, file)
        if (!exists) {
            toneStatusText.text = "当前播放自带$label（demo 或未生成自定义音）"
        } else {
            toneStatusText.text = getString(R.string.tone_playing)
        }
        lifecycleScope.launch(Dispatchers.Main) {
            val player = GreetingPlayer(applicationContext)
            val ms = if (file == Config.GREETING_FILE) player.play() else player.playFarewell()
            if (ms <= 0) toneStatusText.text = "播放失败：$label 音频不可用"
        }
    }

    // ---------------- 启动前校验 ----------------
    private fun prepareConfig(): Boolean {
        saveIdleTimeout()
        if (isDemoMode()) {
            Config.useDemo = true
            return true
        }
        val typed = apiKeyInput.text?.toString()?.trim().orEmpty()
        if (typed.isBlank()) {
            val msg = getString(R.string.error_sdk_need_key)
            stateText.text = msg
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            apiKeyInput.requestFocus()
            return false
        }
        if (!Config.hasSavedApiKey || typed != Config.apiKey) {
            val msg = getString(R.string.error_sdk_need_save)
            stateText.text = msg
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return false
        }
        // 保存 Agent 页尚未点保存的输入，避免用户填了却忘了点。
        Config.persona = personaInput.text?.toString()?.trim().orEmpty()
        Config.instructions = instructionsInput.text?.toString()?.trim().orEmpty()
        Config.dialogueVoice = dialogueVoiceInput.text?.toString()?.trim().orEmpty()
        Config.useDemo = false
        return true
    }

    private fun saveIdleTimeout() {
        val timeout = idleTimeoutInput.text?.toString()?.toLongOrNull() ?: 30_000L
        Config.idleTimeoutMs = timeout.coerceIn(5_000L, 600_000L)
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryStatus()
    }

    private fun refreshBatteryStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            batteryStatusText.text = getString(R.string.battery_unknown)
            ignoreBatteryButton.visibility = View.GONE
            return
        }
        val pm = getSystemService(PowerManager::class.java)
        val ignored = pm?.isIgnoringBatteryOptimizations(packageName) == true
        batteryStatusText.text = getString(
            if (ignored) R.string.battery_ignored else R.string.battery_optimized
        )
        ignoreBatteryButton.visibility = if (ignored) View.GONE else View.VISIBLE
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        val handled = runCatching { startActivity(intent) }.isSuccess
        if (!handled) {
            val detail = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            runCatching { startActivity(detail) }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1001)
    }

    private fun startService(action: String) {
        val intent = Intent(this, WakeService::class.java).setAction(action)
        androidx.core.content.ContextCompat.startForegroundService(this, intent)
    }
}
