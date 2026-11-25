package com.discolow.cadeauxgo.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.discolow.cadeauxgo.R
import com.discolow.cadeauxgo.bot.BotService
import com.discolow.cadeauxgo.bot.BotSettings
import com.discolow.cadeauxgo.bot.Builtin
import com.discolow.cadeauxgo.bot.CrashLog
import com.discolow.cadeauxgo.bot.Role
import com.discolow.cadeauxgo.bot.TemplateStore

class MainActivity : Activity() {

    private lateinit var settings: BotSettings
    private lateinit var ui: Ui
    private val c get() = ui.c

    private lateinit var statusChip: TextView
    private lateinit var setupBody: LinearLayout
    private lateinit var setupState: TextView
    private lateinit var crashBox: LinearLayout
    private lateinit var templatesBox: LinearLayout
    private lateinit var searchView: TextView

    private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = BotSettings(this)
        CrashLog.install(this)
        ui = Ui(this, Palette.of(this))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = c.bg

        val scroll = ScrollView(this).apply { setBackgroundColor(c.bg); isFillViewport = true }
        val root = ui.vertical()
        scroll.addView(root)
        setContentView(scroll)
        // Après setContentView (sinon plantage : la fenêtre n'existe pas encore).
        // Icônes de la barre d'état claires (sur l'en-tête coloré) ; barre de navigation selon le thème.
        val dark = c.bg == Palette(true).bg
        window.insetsController?.setSystemBarsAppearance(
            if (dark) 0 else android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )

        val header = header()
        root.addView(header)
        val content = ui.vertical { setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(32)) }
        root.addView(content)
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            header.setPadding(ui.dp(20), bars.top + ui.dp(20), ui.dp(20), ui.dp(26))
            content.setPadding(ui.dp(16) + bars.left, 0, ui.dp(16) + bars.right, ui.dp(32) + bars.bottom)
            insets
        }

        setupCard(content)
        giftsCard(content)
        transferCard(content)
        learnedCard(content)
        advancedCard(content)

        content.addView(
            ui.muted(
                "⚠️ L'automatisation est interdite par les conditions d'utilisation de Niantic : risque " +
                    "d'avertissement ou de suspension du compte. Tout reste sur ton téléphone, rien n'est envoyé.",
                12f,
            ).apply { gravity = Gravity.CENTER; setPadding(ui.dp(8), 0, ui.dp(8), 0) },
            ui.lp(top = 20),
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------------------------------------------------------------- en-tête

    private fun header(): LinearLayout {
        val h = ui.vertical {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF5E35B1.toInt(), 0xFF8E24AA.toInt(), 0xFFEC407A.toInt()),
            ).apply { cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, ui.dp(28).toFloat(), ui.dp(28).toFloat(), ui.dp(28).toFloat(), ui.dp(28).toFloat()) }
        }
        val row = ui.horizontal()
        // Le logo (rond en dégradé + cadeau), avec un léger liseré blanc pour ressortir sur l'en-tête.
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x40FFFFFF) }
            setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
            elevation = ui.dp(4).toFloat()
        }, LinearLayout.LayoutParams(ui.dp(62), ui.dp(62)).apply { rightMargin = ui.dp(14) })
        val titles = ui.vertical()
        titles.addView(ui.text("Cadeaux GO", 26f, Color.WHITE, bold = true))
        titles.addView(ui.text("Cadeaux et transferts automatiques", 14f, 0xDDFFFFFF.toInt()))
        row.addView(titles, ui.lp(0, weight = 1f))
        h.addView(row)
        statusChip = ui.chip("", Color.WHITE, 0x33FFFFFF)
        h.addView(statusChip, ui.lp(LinearLayout.LayoutParams.WRAP_CONTENT, top = 16))
        return h
    }

    // ---------------------------------------------------------------- cartes

    private fun setupCard(parent: LinearLayout) {
        setupBody = ui.card(parent, R.drawable.ic_bolt, Brand.SETUP, "Démarrage", "Le service qui voit l'écran et touche à ta place")
        setupState = ui.text("", 14.5f)
        setupBody.addView(setupState)
        crashBox = ui.vertical()
        setupBody.addView(crashBox)
    }

    private fun refreshSetup(on: Boolean, running: Boolean) {
        setupBody.removeViews(1, setupBody.childCount - 1)
        setupBody.addView(crashBox)
        when {
            on && running -> {
                setupState.text = "Tout est prêt. Le panneau flottant 🎁 ▶ ♻ 📷 apparaît dans Pokémon GO."
                val row = ui.horizontal()
                row.addView(ui.primaryButton("Lancer Pokémon GO") { launchGame() }, ui.lp(0, weight = 1f))
                row.addView(View(this), LinearLayout.LayoutParams(ui.dp(10), 1))
                row.addView(ui.tonalButton("Afficher le panneau") {
                    BotService.instance?.forceShowPanel()
                    toast("Panneau affiché à gauche de l'écran")
                }, ui.lp(0, weight = 1f))
                setupBody.addView(row, ui.lp(top = 12))
            }
            else -> {
                setupState.text = if (on) {
                    "Le service est activé mais arrêté (planté, ou coupé par le téléphone). Désactive-le puis " +
                        "réactive-le, et mets l'appli en batterie « Non restreinte »."
                } else {
                    "Une seule fois, pour que Cadeaux GO puisse voir l'écran et toucher à ta place :"
                }
                setupBody.addView(ui.step(1, "Ouvre les réglages d'accessibilité."), ui.lp(top = 6))
                setupBody.addView(ui.step(2, "Choisis « Cadeaux GO » et active-le."))
                setupBody.addView(
                    ui.step(3, "Si c'est grisé (Android 13+) : Infos de l'appli → menu ⋮ → « Autoriser les paramètres restreints », puis recommence."),
                )
                setupBody.addView(ui.primaryButton("Ouvrir les réglages d'accessibilité") {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }, ui.lp(top = 12))
                setupBody.addView(ui.tonalButton("Infos de l'appli") {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }, ui.lp(top = 8))
            }
        }
    }

    private fun giftsCard(parent: LinearLayout) {
        val b = ui.card(parent, R.drawable.ic_gift, Brand.LOGO, "Cadeaux", "Bouton ▶ du panneau flottant")
        b.addView(ui.step(1, "Dans le jeu, ouvre ta liste d'amis tout en haut."))
        b.addView(ui.step(2, "Appuie sur ▶ : chaque ami qui t'a envoyé un cadeau et/ou à qui tu n'en as pas encore envoyé est visité."))
        b.addView(ui.step(3, "■ pour arrêter à tout moment."))
        b.addView(ui.divider(), ui.lp(h = 1, top = 8, bottom = 4))
        b.addView(ui.switchRow("Ouvrir les cadeaux reçus", null, settings.doOpen) { settings.doOpen = it })
        b.addView(ui.switchRow("Envoyer des cadeaux", "Toujours le premier cadeau de ta liste", settings.doSend) { settings.doSend = it })
    }

    private fun transferCard(parent: LinearLayout) {
        val b = ui.card(parent, R.drawable.ic_recycle, Brand.TRANSFER, "Transfert", "Bouton ♻ du panneau flottant")
        b.addView(ui.warning("Un transfert est définitif. Garde « Essai à blanc » activé la première fois : tout est sélectionné, puis ANNULER."))
        b.addView(ui.step(1, "Dans le jeu, ouvre la page POKÉMON, triée par date de capture (récents d'abord)."), ui.lp(top = 10))
        b.addView(ui.step(2, "Appuie sur ♻ : Cadeaux GO tape lui-même la recherche ci-dessous, sélectionne et transfère."))

        searchView = ui.text("", 12.5f).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            background = ui.rounded(c.codeBg, 12, c.outline)
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
        }
        b.addView(searchView, ui.lp(top = 10))
        b.addView(ui.tonalButton("Copier la recherche (si besoin)") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Recherche Pokémon", settings.transferSearch()))
            toast("Recherche copiée")
        }, ui.lp(top = 10))
        b.addView(
            ui.muted("Toujours gardés : chromatiques, légendaires, fabuleux, Ultra-Chimères, costumés, chanceux, obscurs, purifiés, favoris, Dynamax, Gigamax, et les Pokémon avec un mot-clé (tag). Pour vérifier un mot-clé, tape-le seul dans la recherche du jeu (ex. « favori » doit afficher tes favoris).", 12.5f),
            ui.lp(top = 8),
        )

        b.addView(ui.divider(), ui.lp(h = 1, top = 10, bottom = 4))
        b.addView(ui.switchRow("Essai à blanc", "Ne transfère rien : ANNULER au lieu de confirmer", settings.transferDryRun) {
            settings.transferDryRun = it
        })
        b.addView(ui.numberRow("Objectif", "Pokémon à transférer en tout", settings.transferTarget) { settings.transferTarget = it })
        b.addView(ui.numberRow("Par transfert", "Maximum par confirmation", settings.transferBatch) { settings.transferBatch = it })
        b.addView(ui.numberRow("PC max", "Au-dessus : gardé", settings.transferMaxCp) { settings.transferMaxCp = it; showSearch() })
        b.addView(ui.numberRow("PV max", "Au-dessus : gardé", settings.transferMaxHp) { settings.transferMaxHp = it; showSearch() })
        b.addView(ui.textArea("Espèces à garder (séparées par des virgules)", settings.transferKeepSpecies, 3) {
            settings.transferKeepSpecies = it; showSearch()
        })
        b.addView(ui.textArea("Recherche personnalisée (vide = automatique)", settings.transferCustomSearch, 2) {
            settings.transferCustomSearch = it; showSearch()
        })
        showSearch()
    }

    private fun learnedCard(parent: LinearLayout) {
        val b = ui.card(
            parent, R.drawable.ic_camera, Brand.LEARN, "Boutons du jeu", "Intégrés · à compléter seulement si besoin",
            collapsible = true, expanded = prefs.getBoolean("learnedOpen", false),
        ) { prefs.edit().putBoolean("learnedOpen", it).apply() }
        b.addView(
            ui.text(
                "Cadeaux GO connaît déjà les boutons du jeu. Si l'un d'eux n'est plus reconnu (mise à jour…) : " +
                    "dans le jeu, appuie sur 📷, entoure l'élément au doigt et choisis ce que c'est.",
                14f,
            ),
        )
        b.addView(ui.switchRow("Utiliser les boutons intégrés", null, settings.useBuiltin) { settings.useBuiltin = it; refresh() })
        templatesBox = ui.vertical()
        b.addView(templatesBox)
        b.addView(ui.tonalButton("Tout désapprendre", c.red) {
            AlertDialog.Builder(this)
                .setMessage("Effacer tous les boutons appris ?")
                .setPositiveButton("Effacer") { _, _ -> TemplateStore.clear(this); refresh() }
                .setNegativeButton("Annuler", null)
                .show()
        }, ui.lp(top = 12))
    }

    private fun advancedCard(parent: LinearLayout) {
        val b = ui.card(
            parent, R.drawable.ic_settings, Brand.SETTINGS, "Réglages avancés", null,
            collapsible = true, expanded = prefs.getBoolean("advancedOpen", false),
        ) { prefs.edit().putBoolean("advancedOpen", it).apply() }
        b.addView(ui.slider("Ressemblance exigée", 60, 97, settings.threshold, " %", "Plus haut = moins d'erreurs, mais risque de ne rien reconnaître.") {
            settings.threshold = it
        })
        b.addView(ui.slider("Attente après chaque action", 400, 3000, settings.delayMs, " ms", "Augmente si ton téléphone est lent.") {
            settings.delayMs = it
        })
        b.addView(ui.numberRow("Amis max par passage", null, settings.maxFriends) { settings.maxFriends = it })
        b.addView(ui.switchRow("Parcourir la liste d'amis", "Désactivé : ancien mode « profil par profil »", settings.listMode) {
            settings.listMode = it
        })
        b.addView(ui.switchRow("Mode profil : glisser vers la gauche", null, settings.swipeLeft) { settings.swipeLeft = it })
        b.addView(ui.switchRow("Panneau dans toutes les applis", null, settings.showEverywhere) {
            settings.showEverywhere = it
            BotService.instance?.refreshPanelVisibility()
        })
    }

    // ---------------------------------------------------------------- état

    private fun serviceEnabled(): Boolean {
        val me = ComponentName(this, BotService::class.java).flattenToString()
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun refresh() {
        val on = serviceEnabled()
        val running = BotService.instance != null
        statusChip.text = when {
            on && running -> "●  Service actif"
            on -> "●  Service arrêté"
            else -> "●  Service à activer"
        }
        statusChip.background = ui.rounded(
            when {
                on && running -> 0x5543A047
                on -> 0x55FB8C00
                else -> 0x33FFFFFF
            },
            50,
        )
        refreshSetup(on, running)
        refreshCrash()
        refreshTemplates()
    }

    private fun refreshCrash() {
        crashBox.removeAllViews()
        val log = CrashLog.read(this) ?: return
        crashBox.addView(
            ui.text("💥 Dernier plantage :\n" + log.lines().take(6).joinToString("\n"), 11.5f, c.red).apply {
                setTextIsSelectable(true)
                background = ui.rounded((c.red and 0x00FFFFFF) or 0x14000000, 12)
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
            },
            ui.lp(top = 12),
        )
        val row = ui.horizontal()
        row.addView(ui.tonalButton("Partager le rapport") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log), "Partager le rapport"))
        }, ui.lp(0, weight = 1f))
        row.addView(View(this), LinearLayout.LayoutParams(ui.dp(10), 1))
        row.addView(ui.tonalButton("Effacer", c.red) { CrashLog.clear(this); refresh() }, ui.lp(0, weight = 1f))
        crashBox.addView(row, ui.lp(top = 8))
    }

    private fun refreshTemplates() {
        templatesBox.removeAllViews()
        val all = TemplateStore.all(this)
        for (role in Role.UI_ORDER) {
            val mine = all.filter { it.role == role }
            val builtin = settings.useBuiltin && Builtin.all.any { it.role == role }
            val required = role == Role.PROFILE || (role == Role.LIST && settings.listMode)
            val (dot, color) = when {
                mine.isNotEmpty() || builtin -> "●" to c.green
                required -> "●" to c.red
                else -> "○" to c.muted
            }
            val detail = listOfNotNull(
                if (builtin) "intégré" else null,
                if (mine.isNotEmpty()) "${mine.size} appris" else null,
            ).joinToString(" + ").ifEmpty { "aucun" }
            val row = ui.horizontal { setPadding(0, ui.dp(7), 0, ui.dp(3)) }
            row.addView(ui.text(dot, 12f, color).apply { setPadding(0, 0, ui.dp(10), 0) })
            row.addView(ui.text(role.label, 14f), ui.lp(0, weight = 1f))
            row.addView(ui.muted(detail, 12.5f))
            templatesBox.addView(row)
            if (mine.isEmpty()) continue
            val strip = ui.horizontal()
            for (t in mine) {
                val cell = ui.vertical { gravity = Gravity.CENTER_HORIZONTAL; setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) }
                cell.addView(ImageView(this).apply {
                    adjustViewBounds = true
                    maxHeight = ui.dp(52)
                    maxWidth = ui.dp(150)
                    background = ui.rounded(c.codeBg, 8)
                    setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
                    setImageBitmap(BitmapFactory.decodeFile(TemplateStore.image(this@MainActivity, t.id).path))
                })
                cell.addView(ui.text("Supprimer", 12f, c.red, bold = true).apply {
                    setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(2))
                    setOnClickListener { TemplateStore.delete(this@MainActivity, t.id); refresh() }
                })
                strip.addView(cell)
            }
            templatesBox.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
        }
    }

    // ---------------------------------------------------------------- divers

    private fun showSearch() {
        searchView.text = settings.transferSearch()
    }

    private fun launchGame() {
        val i = packageManager.getLaunchIntentForPackage(BotService.POGO)
        if (i == null) toast("Pokémon GO n'est pas installé") else startActivity(i)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
