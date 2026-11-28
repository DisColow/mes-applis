package com.discolow.cadeauxgo.bot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.discolow.cadeauxgo.R
import com.discolow.cadeauxgo.ui.Brand
import com.discolow.cadeauxgo.vision.Frame
import com.discolow.cadeauxgo.vision.Resize
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class BotService : AccessibilityService(), BotHost {

    companion object {
        const val POGO = "com.nianticlabs.pokemongo"
        /** Largeur de l'image analysée (les captures sont réduites à cette largeur). */
        const val WORK_WIDTH = 480

        @Volatile
        var instance: BotService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val shotExecutor = Executors.newSingleThreadExecutor()
    private lateinit var wm: WindowManager
    private lateinit var settings: BotSettings

    private var panel: LinearLayout? = null
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var teachBtn: ImageView
    private lateinit var handleBtn: ImageView
    private lateinit var transferBtn: ImageView
    private lateinit var buttonsRow: LinearLayout
    private var savedX = 0
    private var savedY = 0
    private lateinit var playBtn: ImageView
    private lateinit var statusView: TextView
    private var minimized = false
    private var foregroundOk = true
    private var teaching: TeachOverlay? = null

    @Volatile
    private var bot: Bot? = null
    private var lastShot = 0L
    private var fullPixels: IntArray? = null

    // ---------------------------------------------------------------- cycle de vie

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        settings = BotSettings(this)
        homePackages = findHomePackages()
        createPanel()
    }

    /** Applis « écran d'accueil » (lanceur, souvent aussi l'écran des applis récentes). */
    private var homePackages: Set<String> = emptySet()

    private fun findHomePackages(): Set<String> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(i, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .filter { it != "com.android.settings" }
            .toSet()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // On ne masque le panneau QUE sur l'écran d'accueil : les surcouches de jeu
        // (Game Booster, Play Jeux…), dialogues et notifications ne doivent pas le cacher.
        when {
            pkg == POGO || pkg == packageName -> foregroundOk = true
            pkg in homePackages -> foregroundOk = false
            else -> return
        }
        refreshPanelVisibility()
    }

    /** Depuis l'appli : réaffiche le panneau et le remet à sa place. */
    fun forceShowPanel() {
        val p = panel ?: return
        foregroundOk = true
        if (minimized) toggleMinimized()
        panelParams.x = 0
        panelParams.y = resources.displayMetrics.heightPixels / 3
        runCatching { wm.updateViewLayout(p, panelParams) }
        refreshPanelVisibility()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        bot?.stop()
        teaching?.dismiss()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        instance = null
        shotExecutor.shutdownNow()
        super.onDestroy()
    }

    fun refreshPanelVisibility() {
        val p = panel ?: return
        val show = teaching == null && (foregroundOk || bot != null || settings.showEverywhere)
        p.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- panneau flottant

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    private fun roundBg(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    /** Fond rond en dégradé avec effet d'appui. */
    private fun pressable(colors: IntArray) = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(0x55FFFFFF),
        GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { shape = GradientDrawable.OVAL },
        null,
    )

    /** Bouton rond avec une icône vectorielle blanche. */
    private fun button(icon: Int, colors: IntArray, description: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = pressable(colors)
        elevation = dp(2).toFloat()
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createPanel() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(0xEE1E1B24.toInt(), 14).apply { setStroke(dp(1), 0x40B39DDB) }
            elevation = dp(8).toFloat()
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        buttonsRow = row
        val handle = button(R.drawable.ic_gift, Brand.LOGO, "Déplacer / réduire") { toggleMinimized() }
        handleBtn = handle
        playBtn = button(R.drawable.ic_play, Brand.GIFTS, "Cadeaux") { toggleBot(Bot.Mode.GIFTS) }
        transferBtn = button(R.drawable.ic_recycle, Brand.TRANSFER, "Transfert") { toggleBot(Bot.Mode.TRANSFER) }
        teachBtn = button(R.drawable.ic_camera, Brand.LEARN, "Apprendre un bouton") { startTeaching() }
        row.addView(handle)
        row.addView(playBtn)
        row.addView(transferBtn)
        row.addView(teachBtn)
        statusView = TextView(this).apply {
            setTextColor(0xFFECE8F4.toInt())
            textSize = 11f
            maxWidth = dp(200)
            setLineSpacing(0f, 1.1f)
            text = "Prêt · ▶ cadeaux · ♻ transfert"
            background = roundBg(0x22FFFFFF, 8)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        // Dimensions explicites : par défaut, une LinearLayout verticale donne MATCH_PARENT à la ligne,
        // et le panneau prendrait alors la largeur du texte d'état, en coupant le dernier bouton.
        root.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(statusView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(3), dp(4), dp(3), dp(2))
        })

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = resources.displayMetrics.heightPixels / 3
        }

        // Le bouton 🎁 sert aussi de poignée pour déplacer le panneau.
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        handle.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = panelParams.x; startY = panelParams.y; dragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) dragged = true
                    if (dragged) {
                        val m = resources.displayMetrics
                        panelParams.x = (startX + dx.toInt()).coerceIn(0, (m.widthPixels - root.width).coerceAtLeast(0))
                        panelParams.y = (startY + dy.toInt()).coerceIn(0, (m.heightPixels - root.height).coerceAtLeast(0))
                        wm.updateViewLayout(root, panelParams)
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragged) v.performClick()
            }
            true
        }
        panel = root
        wm.addView(root, panelParams)
    }

    private fun toggleMinimized() {
        minimized = !minimized
        playBtn.visibility = if (minimized) View.GONE else View.VISIBLE
        teachBtn.visibility = if (minimized) View.GONE else View.VISIBLE
        transferBtn.visibility = if (minimized) View.GONE else View.VISIBLE
        statusView.visibility = if (minimized) View.GONE else View.VISIBLE
    }

    private fun setPanelTouchable(touchable: Boolean) {
        val p = panel ?: return
        val flags = if (touchable) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (panelParams.flags != flags) {
            panelParams.flags = flags
            runCatching { wm.updateViewLayout(p, panelParams) }
        }
    }

    override fun status(text: String) {
        main.post { statusView.text = text }
    }

    private fun toast(text: String) = main.post { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }

    // ---------------------------------------------------------------- routine

    private fun toggleBot(mode: Bot.Mode) {
        bot?.let { it.stop(); status("Arrêt…"); return }
        val b = Bot(this, settings, { f -> TemplateStore.load(this, f.srcW, f.srcH, f.w, settings.useBuiltin) }, mode)
        bot = b
        playBtn.setImageResource(R.drawable.ic_stop)
        playBtn.contentDescription = "Arrêter"
        playBtn.background = pressable(Brand.STOP)
        setRunLayout(true, mode)
        Thread({
            val reason = b.run()
            main.post {
                bot = null
                setRunLayout(false, mode)
                playBtn.setImageResource(R.drawable.ic_play)
                playBtn.contentDescription = "Cadeaux"
                playBtn.background = pressable(Brand.GIFTS)
                statusView.text = "$reason\n${b.summary()}"
                Toast.makeText(this, "Cadeaux GO : $reason", Toast.LENGTH_LONG).show()
                refreshPanelVisibility()
            }
        }, "cadeaux-bot").start()
    }

    /**
     * Pendant la routine, le panneau se réduit à une petite bande (bouton ■ + état) placée là où il
     * ne cache rien d'utile : en bas à gauche pour les cadeaux, en haut à gauche pour le transfert
     * (vérifié sur de vraies captures, TransferScreensTest).
     */
    private fun setRunLayout(running: Boolean, mode: Bot.Mode) {
        val p = panel ?: return
        if (minimized) toggleMinimized()
        val m = resources.displayMetrics
        if (running) {
            savedX = panelParams.x; savedY = panelParams.y
            p.orientation = LinearLayout.HORIZONTAL
            handleBtn.visibility = View.GONE
            teachBtn.visibility = View.GONE
            transferBtn.visibility = View.GONE
            statusView.textSize = 10f
            statusView.maxLines = 3
            (statusView.layoutParams as LinearLayout.LayoutParams).setMargins(dp(4), dp(3), dp(3), dp(3))
            // Au plus ~38 % de la largeur de l'écran, bouton compris.
            statusView.maxWidth = (m.widthPixels * 0.38f).toInt() - dp(56)
            if (mode == Bot.Mode.TRANSFER) {
                // Transfert : en haut à gauche. En bas, le panneau cacherait « TRANSFÉRER (n) ».
                panelParams.gravity = Gravity.TOP or Gravity.START
            } else {
                // Cadeaux : en bas à gauche (lignes du bas ignorées de toute façon, croix du milieu libre).
                panelParams.gravity = Gravity.BOTTOM or Gravity.START
            }
            panelParams.x = 0
            panelParams.y = dp(4)
        } else {
            p.orientation = LinearLayout.VERTICAL
            handleBtn.visibility = View.VISIBLE
            teachBtn.visibility = View.VISIBLE
            transferBtn.visibility = View.VISIBLE
            statusView.textSize = 11f
            statusView.maxLines = Int.MAX_VALUE
            statusView.maxWidth = dp(200)
            (statusView.layoutParams as LinearLayout.LayoutParams).setMargins(dp(3), dp(4), dp(3), dp(2))
            panelParams.gravity = Gravity.TOP or Gravity.START
            panelParams.x = savedX
            panelParams.y = savedY
        }
        runCatching { wm.updateViewLayout(p, panelParams) }
    }

    // ---------------------------------------------------------------- capture d'écran

    /** Capture pleine résolution (logiciel, ARGB_8888). Ne pas appeler sur le thread principal. */
    fun screenshot(): Bitmap? {
        for (attempt in 0 until 3) {
            // L'API limite à ~3 captures par seconde.
            val wait = lastShot + 380 - SystemClock.uptimeMillis()
            if (wait > 0) Thread.sleep(wait)
            val latch = CountDownLatch(1)
            var result: Bitmap? = null
            takeScreenshot(Display.DEFAULT_DISPLAY, shotExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(shot: ScreenshotResult) {
                    try {
                        val hb = shot.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, shot.colorSpace)
                        result = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        hb.close()
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            })
            latch.await(3, TimeUnit.SECONDS)
            lastShot = SystemClock.uptimeMillis()
            if (result != null) return result
        }
        return null
    }

    override fun capture(): Frame? {
        val full = screenshot() ?: return null
        val n = full.width * full.height
        // Tampon réutilisé : une capture pleine résolution pèse plusieurs Mo.
        val buf = fullPixels?.takeIf { it.size == n } ?: IntArray(n).also { fullPixels = it }
        full.getPixels(buf, 0, full.width, 0, 0, full.width, full.height)
        val frame = Resize.frame(buf, full.width, full.height, WORK_WIDTH)
        full.recycle()
        return frame
    }

    // ---------------------------------------------------------------- gestes

    private fun gesture(path: Path, durationMs: Long): Boolean = gesture(listOf(path to durationMs))

    /**
     * Geste en plusieurs segments SANS lever le doigt entre eux (continueStroke) : sert à
     * glisser puis rester immobile avant de lâcher, pour que la liste ne parte pas sur son élan.
     */
    private fun gesture(segments: List<Pair<Path, Long>>): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val total = segments.sumOf { it.second }
        main.post {
            // Le panneau laisse passer le geste s'il se trouve dessous.
            setPanelTouchable(false)
            fun finish(success: Boolean) {
                ok = success; setPanelTouchable(true); latch.countDown()
            }
            fun dispatch(i: Int, previous: GestureDescription.StrokeDescription?) {
                val (path, ms) = segments[i]
                val last = i == segments.lastIndex
                val stroke = previous?.continueStroke(path, 0, ms, !last)
                    ?: GestureDescription.StrokeDescription(path, 0, ms, !last)
                val g = GestureDescription.Builder().addStroke(stroke).build()
                val cb = object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        if (last) finish(true) else dispatch(i + 1, stroke)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription) = finish(false)
                }
                if (!dispatchGesture(g, cb, main)) finish(false)
            }
            main.postDelayed({ dispatch(0, null) }, 60)
        }
        latch.await(total + 3000, TimeUnit.MILLISECONDS)
        return ok
    }

    override fun tap(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x.coerceAtLeast(0f), y.coerceAtLeast(0f)) }
        return gesture(p, 60)
    }

    override fun swipeNextFriend(left: Boolean): Boolean {
        val m = resources.displayMetrics
        val bounds = wm.currentWindowMetrics.bounds
        val w = bounds.width().toFloat().takeIf { it > 0 } ?: m.widthPixels.toFloat()
        val h = bounds.height().toFloat().takeIf { it > 0 } ?: m.heightPixels.toFloat()
        val y = h * 0.40f
        val (x1, x2) = if (left) w * 0.85f to w * 0.15f else w * 0.15f to w * 0.85f
        val p = Path().apply { moveTo(x1, y); lineTo(x2, y) }
        return gesture(p, 300)
    }

    override fun scrollList(down: Boolean, fast: Boolean): Boolean {
        val bounds = wm.currentWindowMetrics.bounds
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (fast) {
            val (y1, y2) = if (down) h * 0.72f to h * 0.38f else h * 0.30f to h * 0.85f
            return gesture(Path().apply { moveTo(w * 0.5f, y1); lineTo(w * 0.5f, y2) }, 150)
        }
        // Glissement court (~1,5 ligne de Pokémon) puis doigt immobile 0,4 s avant de lâcher :
        // la liste n'a plus d'élan et s'arrête exactement où le doigt s'arrête → aucune ligne sautée.
        val x = w * 0.5f
        val (y1, y2) = if (down) h * 0.68f to h * 0.46f else h * 0.46f to h * 0.68f
        val move = Path().apply { moveTo(x, y1); lineTo(x, y2) }
        val hold = Path().apply { moveTo(x, y2); lineTo(x, y2 + 1f) }
        return gesture(listOf(move to 600L, hold to 400L))
    }

    override fun drag(fromY: Float, toY: Float): Boolean {
        val x = wm.currentWindowMetrics.bounds.width() * 0.5f
        val move = Path().apply { moveTo(x, fromY); lineTo(x, toY) }
        val hold = Path().apply { moveTo(x, toY); lineTo(x, toY + 1f) }
        // Vitesse modérée (~1,5 px/ms) puis doigt immobile : pas d'élan, la liste s'arrête net.
        val ms = (abs(toY - fromY) / 1.5f).toLong().coerceIn(250L, 900L)
        return gesture(listOf(move to ms, hold to 350L))
    }

    override fun longPress(x: Float, y: Float, durationMs: Long): Boolean {
        val p = Path().apply { moveTo(x.coerceAtLeast(0f), y.coerceAtLeast(0f)) }
        return gesture(p, durationMs)
    }

    /**
     * Tape [text] dans le champ de saisie ouvert par le jeu (le jeu affiche un vrai champ Android
     * au-dessus du clavier), relit le texte pour vérifier, puis valide (« OK » ou touche Entrée).
     */
    override fun typeText(text: String): Boolean {
        repeat(20) {
            val field = findNode { it.isEditable }
            if (field != null) {
                val args = android.os.Bundle().apply {
                    putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                field.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Thread.sleep(250)
                field.refresh()
                if (field.text?.toString() != text) return false
                val ok = findNode { n ->
                    n.text?.toString()?.trim()?.equals("OK", ignoreCase = true) == true && (n.isClickable || n.parent?.isClickable == true)
                }
                val done = when {
                    ok?.isClickable == true -> ok.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    ok?.parent?.isClickable == true -> ok.parent.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    else -> false
                }
                return done || field.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            Thread.sleep(150)
        }
        return false
    }

    /** Premier nœud (toutes fenêtres) qui vérifie [match], en largeur d'abord. */
    private fun findNode(match: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): android.view.accessibility.AccessibilityNodeInfo? {
        val roots = (windows.mapNotNull { it.root } + listOfNotNull(rootInActiveWindow)).distinct()
        for (root in roots) {
            val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            queue.add(root)
            var seen = 0
            while (queue.isNotEmpty() && seen < 400) {
                val n = queue.removeFirst()
                seen++
                if (match(n)) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    override fun back() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // ---------------------------------------------------------------- apprentissage

    private fun startTeaching() {
        if (bot != null) {
            toast("Arrête la routine avant d'apprendre un bouton"); return
        }
        if (teaching != null) return
        panel?.visibility = View.GONE
        Thread({
            Thread.sleep(250) // laisser le panneau disparaître de l'écran
            val shot = screenshot()
            main.post {
                if (shot == null) {
                    toast("Capture d'écran impossible")
                    refreshPanelVisibility()
                    return@post
                }
                val t = TeachOverlay(this, wm, shot) {
                    teaching = null
                    refreshPanelVisibility()
                }
                teaching = t
                t.show()
            }
        }, "cadeaux-shot").start()
    }
}
