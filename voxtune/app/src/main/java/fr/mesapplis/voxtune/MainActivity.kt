package fr.mesapplis.voxtune

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import fr.mesapplis.voxtune.dsp.Music
import fr.mesapplis.voxtune.dsp.Scale
import fr.mesapplis.voxtune.dsp.ScaleQuantizer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private companion object {
        const val REQ_MIC = 1
        const val ACCENT = 0xFFFF4FA3.toInt()
        const val BG = 0xFF0E0C14.toInt()
        const val CARD = 0xFF1C1926.toInt()
    }

    private lateinit var engine: AudioEngine
    private val prefs by lazy { getSharedPreferences("voxtune", Context.MODE_PRIVATE) }
    private val ui = Handler(Looper.getMainLooper())
    private val graphQuantizer = ScaleQuantizer()

    private lateinit var graph: PitchGraphView
    private lateinit var noteText: TextView
    private lateinit var infoText: TextView
    private lateinit var levelBar: View
    private lateinit var startBtn: Button
    private lateinit var recBtn: Button
    private lateinit var keySpinner: Spinner
    private lateinit var scaleSpinner: Spinner
    private val sliders = mutableMapOf<String, SeekBar>()

    // Onglets et mode Studio
    private var studioMode = false
    private lateinit var tabLive: Button
    private lateinit var tabStudio: Button
    private lateinit var liveSection: View
    private lateinit var studioSection: View
    private lateinit var monitorSwitch: View
    private lateinit var studioStatus: TextView
    private lateinit var studioRecBtn: Button
    private lateinit var studioPlayBtn: Button
    private lateinit var studioSaveBtn: Button
    private lateinit var studioSeek: SeekBar
    private lateinit var studioTime: TextView
    private lateinit var originalSwitch: Switch
    private var userSeeking = false
    private var exporting = false

    private val d get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).roundToInt()

    private val tick = object : Runnable {
        override fun run() {
            updateDisplay()
            ui.postDelayed(this, 33)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = AudioEngine(getSystemService(AudioManager::class.java))
        engine.onEnded = { err ->
            ui.post {
                if (err != null) Toast.makeText(this, "Erreur audio : $err", Toast.LENGTH_LONG).show()
                else if (studioMode && engine.take != null && engine.playPos == 0) {
                    Toast.makeText(this, "Durée maximale atteinte", Toast.LENGTH_SHORT).show()
                }
                refreshButtons()
            }
        }
        setContentView(buildUi())
        loadSettings()
        setStudioMode(prefs.getBoolean("studio", false))
    }

    // ---------------------------------------------------------------- UI

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(CARD); cornerRadius = 18f * d }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }
    }

    private fun label(text: String, size: Float = 14f, color: Int = 0xCCFFFFFF.toInt()) =
        TextView(this).apply { this.text = text; textSize = size; setTextColor(color) }

    private fun pillButton(text: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply { setColor(color); cornerRadius = 40f * d }
        setOnClickListener { onClick() }
    }

    private fun slider(
        parent: LinearLayout, key: String, title: String, max: Int, def: Int,
        format: (Int) -> String, apply: (Int) -> Unit,
    ) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val t = label(title).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
        val v = label("", 14f, ACCENT).apply { typeface = Typeface.DEFAULT_BOLD }
        row.addView(t); row.addView(v)
        val sb = SeekBar(this).apply {
            this.max = max
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            setPadding(dp(4), dp(10), dp(4), dp(10))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    v.text = format(p); apply(p)
                    prefs.edit().putInt(key, p).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        sb.progress = prefs.getInt(key, def)
        v.text = format(sb.progress); apply(sb.progress)
        sliders[key] = sb
        parent.addView(row)
        parent.addView(sb)
    }

    private fun spinner(items: List<String>, onSelect: (Int) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onSelect(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(root)
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        }

        root.addView(label("VoxTune", 28f, Color.WHITE).apply {
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            setPadding(0, dp(8), 0, 0)
        })
        root.addView(label("Correction de la voix en temps réel", 13f, 0x88FFFFFF.toInt()))

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = 40f * d }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        tabLive = pillButton("🎙  Direct", ACCENT) { setStudioMode(false) }.apply {
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
        }
        tabStudio = pillButton("🎧  Studio", CARD) { setStudioMode(true) }.apply {
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
        }
        tabs.addView(tabLive); tabs.addView(tabStudio)
        root.addView(tabs)

        // Affichage : note + graphe
        val display = card()
        val noteRow = LinearLayout(this).apply { gravity = Gravity.BOTTOM }
        noteText = label("—", 44f, Color.WHITE).apply {
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        infoText = label("", 13f, 0x99FFFFFF.toInt()).apply { gravity = Gravity.END }
        noteRow.addView(noteText); noteRow.addView(infoText)
        display.addView(noteRow)
        levelBar = View(this).apply {
            setBackgroundColor(ACCENT)
            layoutParams = LinearLayout.LayoutParams(0, dp(3)).apply { bottomMargin = dp(8) }
        }
        display.addView(levelBar)
        graph = PitchGraphView(this).apply {
            quantizer = graphQuantizer
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(200))
            clipToOutline = true
            background = GradientDrawable().apply { setColor(0xFF15131C.toInt()); cornerRadius = 12f * d }
        }
        display.addView(graph)
        root.addView(display)

        // Boutons
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        startBtn = pillButton("▶  Démarrer", ACCENT) { toggleEngine() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(54), 1.4f).apply { marginEnd = dp(10) }
        }
        recBtn = pillButton("●  Enregistrer", 0xFF3A3548.toInt()) { toggleRecording() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(54), 1f)
        }
        btnRow.addView(startBtn); btnRow.addView(recBtn)
        liveSection = btnRow
        root.addView(btnRow)
        studioSection = buildStudio()
        root.addView(studioSection)

        // Tonalité
        val tone = card()
        tone.addView(label("Tonalité & gamme", 16f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD })
        val toneRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keySpinner = spinner(Music.NOTE_NAMES.toList()) { pos ->
            engine.processor.key = pos; graph.keyRoot = pos; syncGraphScale()
            prefs.edit().putInt("key", pos).apply()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
        scaleSpinner = spinner(Scale.entries.map { it.label }) { pos ->
            engine.processor.scale = Scale.entries[pos]; syncGraphScale()
            prefs.edit().putInt("scale", pos).apply()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 2f) }
        toneRow.addView(keySpinner); toneRow.addView(scaleSpinner)
        tone.addView(toneRow)
        root.addView(tone)

        // Préréglages
        val presets = card()
        presets.addView(label("Préréglages", 16f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD })
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        fun preset(name: String, speed: Int, amount: Int) =
            pillButton(name, 0xFF2C2838.toInt()) {
                sliders["speed"]?.progress = speed
                sliders["amount"]?.progress = amount
            }.apply {
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) }
            }
        presetRow.addView(preset("🤖 Robot", 0, 100))
        presetRow.addView(preset("🎤 Pop", 25, 100))
        presetRow.addView(preset("🌿 Naturel", 90, 80))
        presets.addView(presetRow)
        root.addView(presets)

        // Réglages
        val settings = card()
        settings.addView(label("Réglages", 16f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD })
        slider(settings, "speed", "Vitesse de correction", 200, 20,
            { if (it == 0) "Instantanée" else "$it ms" }) { engine.processor.retuneMs = it.toFloat() }
        slider(settings, "amount", "Intensité", 100, 100, { "$it %" }) {
            engine.processor.amount = it / 100f
        }
        slider(settings, "transpose", "Transposition", 24, 12,
            { val s = it - 12; if (s > 0) "+$s demi-tons" else "$s demi-tons" }) {
            engine.processor.transpose = it - 12
        }
        slider(settings, "inGain", "Gain micro", 400, 100, { "$it %" }) {
            engine.processor.inputGain = it / 100f
        }
        slider(settings, "outGain", "Volume de sortie", 200, 100, { "$it %" }) {
            engine.processor.outputGain = it / 100f
        }
        val monitor = Switch(this).apply {
            text = "Retour dans le casque"
            setTextColor(0xCCFFFFFF.toInt())
            isChecked = prefs.getBoolean("monitor", true)
            engine.monitoring = isChecked
            setOnCheckedChangeListener { _, c ->
                engine.monitoring = c; prefs.edit().putBoolean("monitor", c).apply()
            }
            setPadding(0, dp(8), 0, 0)
        }
        settings.addView(monitor)
        monitorSwitch = monitor
        val bypass = Switch(this).apply {
            text = "Correction activée"
            setTextColor(0xCCFFFFFF.toInt())
            isChecked = true
            setOnCheckedChangeListener { _, c -> engine.processor.enabled = c }
            setPadding(0, dp(8), 0, 0)
        }
        settings.addView(bypass)
        root.addView(settings)

        root.addView(label(
            "Direct : utilisez un casque filaire pour éviter le larsen (le Bluetooth ajoute un retard).\n" +
                "Studio : pas besoin de casque — chantez, puis réécoutez avec la correction et " +
                "ajustez les réglages pendant l'écoute.\n" +
                "Les enregistrements sont sauvegardés dans Musique/VoxTune.",
            12f, 0x77FFFFFF.toInt()
        ).apply { setPadding(dp(4), dp(16), dp(4), 0) })

        return scroll
    }

    private fun buildStudio(): View {
        val c = card()
        studioStatus = label("Enregistrez une prise, puis écoutez-la corrigée.", 13f, 0x99FFFFFF.toInt())
        c.addView(studioStatus)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        studioRecBtn = pillButton("●  Enregistrer", 0xFFE0303A.toInt()) { studioRecord() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(10) }
        }
        studioPlayBtn = pillButton("▶  Écouter", ACCENT) { studioPlay() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(54), 1f)
        }
        row.addView(studioRecBtn); row.addView(studioPlayBtn)
        c.addView(row)

        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        studioSeek = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) updateStudioTime(p)
                }
                override fun onStartTrackingTouch(s: SeekBar) { userSeeking = true }
                override fun onStopTrackingTouch(s: SeekBar) {
                    userSeeking = false
                    val t = engine.take ?: return
                    val pos = (t.length.toLong() * s.progress / 1000).toInt()
                    if (engine.mode == AudioEngine.Mode.PLAYBACK) engine.seekRequest = pos
                    else pendingSeek = pos
                }
            })
        }
        studioTime = label("0:00 / 0:00", 12f, 0x99FFFFFF.toInt()).apply { setPadding(dp(8), 0, 0, 0) }
        seekRow.addView(studioSeek); seekRow.addView(studioTime)
        c.addView(seekRow)

        originalSwitch = Switch(this).apply {
            text = "Entendre l'original (sans correction)"
            setTextColor(0xCCFFFFFF.toInt())
            setOnCheckedChangeListener { _, v -> engine.playOriginal = v }
            setPadding(0, dp(8), 0, 0)
        }
        c.addView(originalSwitch)
        c.addView(Switch(this).apply {
            text = "Lecture en boucle"
            setTextColor(0xCCFFFFFF.toInt())
            isChecked = prefs.getBoolean("loop", true)
            engine.looping = isChecked
            setOnCheckedChangeListener { _, v ->
                engine.looping = v; prefs.edit().putBoolean("loop", v).apply()
            }
            setPadding(0, dp(8), 0, 0)
        })

        studioSaveBtn = pillButton("💾  Sauvegarder la version corrigée", 0xFF3A3548.toInt()) { studioExport() }.apply {
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(46)).apply { topMargin = dp(12) }
        }
        c.addView(studioSaveBtn)
        return c
    }

    private var pendingSeek = -1

    private fun setStudioMode(studio: Boolean) {
        if (engine.running) {
            finishRecording()
            engine.stop()
        }
        studioMode = studio
        prefs.edit().putBoolean("studio", studio).apply()
        liveSection.visibility = if (studio) View.GONE else View.VISIBLE
        studioSection.visibility = if (studio) View.VISIBLE else View.GONE
        monitorSwitch.visibility = if (studio) View.GONE else View.VISIBLE
        (tabLive.background as GradientDrawable).setColor(if (studio) CARD else ACCENT)
        (tabStudio.background as GradientDrawable).setColor(if (studio) ACCENT else CARD)
        refreshButtons()
    }

    private fun studioRecord() {
        when (engine.mode) {
            AudioEngine.Mode.CAPTURE -> {
                engine.stop()
                refreshButtons()
            }
            else -> {
                if (!hasMic()) {
                    pendingAction = { studioRecord() }
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                    return
                }
                engine.stop()
                pendingSeek = -1
                engine.startCapture()
                refreshButtons()
            }
        }
    }

    private fun studioPlay() {
        if (engine.mode == AudioEngine.Mode.PLAYBACK) {
            engine.stop()
        } else {
            if (engine.mode == AudioEngine.Mode.CAPTURE) engine.stop()
            if (!engine.startPlayback()) {
                Toast.makeText(this, "Enregistrez d'abord une prise.", Toast.LENGTH_SHORT).show()
            } else if (pendingSeek >= 0) {
                engine.seekRequest = pendingSeek
                pendingSeek = -1
            }
        }
        refreshButtons()
    }

    private fun studioExport() {
        val take = engine.take
        if (take == null || take.length == 0 || engine.mode == AudioEngine.Mode.CAPTURE) {
            Toast.makeText(this, "Enregistrez d'abord une prise.", Toast.LENGTH_SHORT).show()
            return
        }
        if (exporting) return
        exporting = true
        refreshButtons()
        val settings = fr.mesapplis.voxtune.dsp.AutoTuneProcessor(take.sampleRate)
            .apply { copySettingsFrom(engine.processor) }
        Thread {
            val file = File(cacheDir, "studio_${System.currentTimeMillis()}.wav")
            try {
                val w = fr.mesapplis.voxtune.dsp.WavWriter(file, take.sampleRate)
                take.render(settings, w)
                w.close()
                saveToMusic(file)
            } catch (t: Throwable) {
                file.delete()
                ui.post { Toast.makeText(this, "Échec de l'export", Toast.LENGTH_LONG).show() }
            }
            ui.post { exporting = false; refreshButtons() }
        }.start()
    }

    private fun fmtTime(samples: Long, sr: Int): String {
        val sec = (samples / sr).toInt()
        return "%d:%02d".format(sec / 60, sec % 60)
    }

    private fun updateStudioTime(progress: Int) {
        val t = engine.take
        if (t == null) { studioTime.text = "0:00 / 0:00"; return }
        val pos = t.length.toLong() * progress / 1000
        studioTime.text = fmtTime(pos, t.sampleRate) + " / " + fmtTime(t.length.toLong(), t.sampleRate)
    }

    private fun updateStudio() {
        val t = engine.take
        when (engine.mode) {
            AudioEngine.Mode.CAPTURE -> {
                val len = t?.length?.toLong() ?: 0L
                val blink = (System.currentTimeMillis() / 500) % 2 == 0L
                studioStatus.text = (if (blink) "● " else "   ") + "Enregistrement… " +
                    fmtTime(len, engine.sampleRate) + "  (max ${engine.maxTakeSeconds / 60} min)"
                studioStatus.setTextColor(0xFFFF6B73.toInt())
                studioTime.text = fmtTime(len, engine.sampleRate)
                studioSeek.progress = 0
            }
            else -> {
                studioStatus.setTextColor(0x99FFFFFF.toInt())
                studioStatus.text = when {
                    t == null || t.length == 0 -> "Enregistrez une prise, puis écoutez-la corrigée."
                    engine.mode == AudioEngine.Mode.PLAYBACK ->
                        "Lecture — modifiez les réglages, l'effet change en direct."
                    else -> "Prise de " + fmtTime(t.length.toLong(), t.sampleRate) + " prête."
                }
                if (t != null && t.length > 0 && !userSeeking) {
                    val pos = if (engine.mode == AudioEngine.Mode.PLAYBACK) engine.playPos
                    else if (pendingSeek >= 0) pendingSeek else engine.playPos
                    studioSeek.progress = (pos.toLong() * 1000 / t.length).toInt()
                    updateStudioTime(studioSeek.progress)
                }
            }
        }
    }

    private fun loadSettings() {
        keySpinner.setSelection(prefs.getInt("key", 0))
        scaleSpinner.setSelection(prefs.getInt("scale", Scale.MAJOR.ordinal).coerceIn(0, Scale.entries.size - 1))
    }

    private fun syncGraphScale() {
        graphQuantizer.set(engine.processor.key, engine.processor.scale)
        graph.invalidate()
    }

    private fun updateDisplay() {
        val p = engine.processor
        val det = p.detectedMidi
        val target = p.targetMidi
        if (!det.isNaN() && target != Int.MIN_VALUE) {
            val shown = target + p.transpose
            noteText.text = "${Music.noteName(shown)}${Music.octave(shown)}"
            val cents = ((det - target) * 100).roundToInt()
            val sign = if (cents > 0) "+" else ""
            infoText.text = "${Music.midiToHz(det).roundToInt()} Hz\n$sign$cents cents"
            noteText.setTextColor(Color.WHITE)
        } else {
            noteText.setTextColor(0x55FFFFFF)
            infoText.text = when (engine.mode) {
                AudioEngine.Mode.LIVE, AudioEngine.Mode.CAPTURE -> "Chantez…"
                else -> ""
            }
        }
        val lp = levelBar.layoutParams
        lp.width = ((graph.width) * p.inputLevel.coerceIn(0f, 1f)).roundToInt()
        levelBar.layoutParams = lp
        graph.push(det, p.outputMidi)

        if (studioMode) updateStudio()
        if (engine.isRecording) {
            recBtn.alpha = if ((System.currentTimeMillis() / 500) % 2 == 0L) 1f else 0.6f
        }
    }

    private fun refreshButtons() {
        if (::studioRecBtn.isInitialized) {
            val capturing = engine.mode == AudioEngine.Mode.CAPTURE
            val playing = engine.mode == AudioEngine.Mode.PLAYBACK
            studioRecBtn.text = if (capturing) "■  Stop" else if (engine.take != null) "●  Refaire" else "●  Enregistrer"
            studioPlayBtn.text = if (playing) "❚❚  Pause" else "▶  Écouter"
            studioPlayBtn.isEnabled = !capturing
            studioPlayBtn.alpha = if (capturing) 0.4f else 1f
            studioSaveBtn.text = if (exporting) "Export en cours…" else "💾  Sauvegarder la version corrigée"
            studioSaveBtn.isEnabled = !exporting
        }
        val on = engine.mode == AudioEngine.Mode.LIVE
        startBtn.text = if (on) "■  Arrêter" else "▶  Démarrer"
        (startBtn.background as GradientDrawable).setColor(if (on) 0xFF5A5470.toInt() else ACCENT)
        val rec = engine.isRecording
        recBtn.text = if (rec) "■  Stop" else "●  Enregistrer"
        recBtn.alpha = 1f
        (recBtn.background as GradientDrawable).setColor(if (rec) 0xFFE0303A.toInt() else 0xFF3A3548.toInt())
        if (engine.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------------------------------------------------------- Audio

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private var pendingAction: (() -> Unit)? = null

    private fun toggleEngine() {
        if (engine.mode == AudioEngine.Mode.LIVE) {
            finishRecording()
            engine.stop()
            refreshButtons()
            return
        }
        if (!hasMic()) {
            pendingAction = { toggleEngine() }
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (engine.monitoring && !headphonesConnected()) {
            AlertDialog.Builder(this)
                .setTitle("Pas de casque détecté")
                .setMessage("Sans casque, le haut-parleur risque de produire un larsen (sifflement). " +
                    "Vous pouvez aussi désactiver « Retour dans le casque » et juste enregistrer.")
                .setPositiveButton("Continuer") { _, _ -> startEngine() }
                .setNegativeButton("Annuler", null)
                .show()
        } else {
            startEngine()
        }
    }

    private fun startEngine() {
        engine.startLive()
        refreshButtons()
    }

    @android.annotation.SuppressLint("InlinedApi") // constante inlinée, sans effet avant API 31
    private fun headphonesConnected(): Boolean {
        val am = getSystemService(AudioManager::class.java)
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type in setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
            )
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_MIC) {
            val action = pendingAction
            pendingAction = null
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) action?.invoke()
            else Toast.makeText(this, "L'accès au micro est nécessaire.", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleRecording() {
        if (engine.isRecording) {
            finishRecording()
            refreshButtons()
            return
        }
        if (engine.mode != AudioEngine.Mode.LIVE) {
            toggleEngine()
            if (engine.mode != AudioEngine.Mode.LIVE) return
        }
        val file = File(cacheDir, "rec_${System.currentTimeMillis()}.wav")
        engine.startRecording(file)
        refreshButtons()
    }

    private fun finishRecording() {
        val w = engine.stopRecording() ?: return
        val src = w.file
        if (w.durationSeconds < 0.3f) { src.delete(); return }
        Thread { saveToMusic(src) }.start()
    }

    /** Copie un WAV dans Musique/VoxTune (MediaStore) puis supprime la source. Thread d'arrière-plan. */
    private fun saveToMusic(src: File) {
        val name = "VoxTune_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date()) + ".wav"
        val ok = try {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/VoxTune")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            true
        } catch (t: Throwable) {
            false
        } finally {
            src.delete()
        }
        ui.post {
            Toast.makeText(
                this,
                if (ok) "Enregistré : Musique/VoxTune/$name" else "Échec de la sauvegarde",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    override fun onStop() {
        super.onStop()
        // Android interdit le micro en arrière-plan sans service : on arrête proprement.
        if (engine.running) {
            finishRecording()
            engine.stop()
            refreshButtons()
        }
    }
}
