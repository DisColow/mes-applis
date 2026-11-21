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
        engine.onError = { msg ->
            ui.post {
                Toast.makeText(this, "Erreur audio : $msg", Toast.LENGTH_LONG).show()
                refreshButtons()
            }
        }
        setContentView(buildUi())
        loadSettings()
        refreshButtons()
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
        root.addView(btnRow)

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
            "🎧 Utilisez un casque filaire pour éviter le larsen. Le Bluetooth ajoute un retard audible.\n" +
                "Les enregistrements sont sauvegardés dans Musique/VoxTune.",
            12f, 0x77FFFFFF.toInt()
        ).apply { setPadding(dp(4), dp(16), dp(4), 0) })

        return scroll
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
            infoText.text = if (engine.running) "Chantez…" else ""
        }
        val lp = levelBar.layoutParams
        lp.width = ((graph.width) * p.inputLevel.coerceIn(0f, 1f)).roundToInt()
        levelBar.layoutParams = lp
        graph.push(det, p.outputMidi)

        if (engine.isRecording) {
            recBtn.alpha = if ((System.currentTimeMillis() / 500) % 2 == 0L) 1f else 0.6f
        }
    }

    private fun refreshButtons() {
        val on = engine.running
        startBtn.text = if (on) "■  Arrêter" else "▶  Démarrer"
        (startBtn.background as GradientDrawable).setColor(if (on) 0xFF5A5470.toInt() else ACCENT)
        val rec = engine.isRecording
        recBtn.text = if (rec) "■  Stop" else "●  Enregistrer"
        recBtn.alpha = 1f
        (recBtn.background as GradientDrawable).setColor(if (rec) 0xFFE0303A.toInt() else 0xFF3A3548.toInt())
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------------------------------------------------------- Audio

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun toggleEngine() {
        if (engine.running) {
            finishRecording()
            engine.stop()
            refreshButtons()
            return
        }
        if (!hasMic()) {
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
        engine.start()
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
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleEngine()
            else Toast.makeText(this, "L'accès au micro est nécessaire.", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleRecording() {
        if (engine.isRecording) {
            finishRecording()
            refreshButtons()
            return
        }
        if (!engine.running) {
            toggleEngine()
            if (!engine.running) return
        }
        val file = File(cacheDir, "rec_${System.currentTimeMillis()}.wav")
        engine.startRecording(file)
        refreshButtons()
    }

    private fun finishRecording() {
        val w = engine.stopRecording() ?: return
        val src = w.file
        if (w.durationSeconds < 0.3f) { src.delete(); return }
        Thread {
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
        }.start()
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
