package fr.mesapplis.relance

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private var tasksBox: LinearLayout? = null

    companion object {
        private const val RC_AUTH = 1
        private const val RC_PERMS = 2
        private const val ACCENT = 0xFF3F51B5.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        window.decorView.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        if (missingPermissions().isNotEmpty()) requestPermissions(missingPermissions(), RC_PERMS)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // ---------------- Écran ----------------

    private fun render() {
        root.removeAllViews()
        root.addView(text("Relance", 26f, bold = true))
        root.addView(text("Après chaque événement de ton agenda, je te demande si c'est fait. " +
            "Et je te relance sur tes tâches Google Tasks en retard ou à venir.", 14f, color = Color.GRAY))

        // Autorisations
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            section("Autorisations")
            root.addView(text("Il me faut l'accès à ton agenda (pour le lire et y cocher ce qui est fait) et le droit d'afficher des notifications.", 14f))
            root.addView(button("Autoriser") { requestPermissions(missing, RC_PERMS) })
        }

        // Événements terminés sans réponse
        val pending = runCatching { CalendarRepo.unanswered(this, prefs) }.getOrDefault(emptyList())
        if (pending.isNotEmpty()) {
            section("À faire le point (${pending.size})")
            pending.take(10).forEach { root.addView(eventRow(it)) }
        }

        // Google Tasks
        section("Google Tasks")
        if (prefs.tasksConnected) {
            root.addView(text("Connecté" + (prefs.account?.let { " : $it" } ?: ""), 15f))
            root.addView(row(
                button("Me relancer maintenant") { runRemind() },
                button("Déconnecter") {
                    prefs.tasksConnected = false; prefs.account = null; render()
                },
            ))
            tasksBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            root.addView(tasksBox)
            loadTasks()
        } else {
            root.addView(text("Pas encore connecté. Sans connexion, seules les questions après les événements fonctionnent.", 14f))
            root.addView(button("Se connecter à Google Tasks") { connect() })
            root.addView(text("Première fois ? Il faut d'abord déclarer l'appli dans Google Cloud (voir le README) " +
                "avec ces informations :", 13f, color = Color.GRAY))
            val info = "Nom du package : $packageName\nEmpreinte SHA-1 : ${sha1()}"
            root.addView(text(info, 13f, mono = true))
            root.addView(button("Copier") {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Relance", info))
                toast("Copié")
            })
        }

        // Quand me déranger
        section("Quand me déranger")
        val reason = Checks.busyReason(this)
        val waiting = Checks.waitingCount(this)
        root.addView(text(
            (if (reason == null) "🟢 En ce moment : je peux te notifier."
            else "⏸ En ce moment j'attends : $reason.") +
                (if (waiting > 0) "\n$waiting question${if (waiting > 1) "s" else ""} en attente." else ""),
            14f, color = if (reason == null) Color.rgb(46, 125, 50) else Color.DKGRAY))
        root.addView(text("Je ne t'envoie les questions et les relances qu'en journée, et quand ton agenda " +
            "ne montre aucun événement en cours (ni qui commence dans les 5 minutes). Sinon j'attends le prochain moment libre.",
            13f, color = Color.GRAY))
        root.addView(check("Attendre que mon agenda soit libre", prefs.waitUntilFree) { prefs.waitUntilFree = it; Scheduler.schedule(this) })
        val dayStart = EditText(this).apply {
            setText(Logic.formatTime(Logic.parseTime(prefs.dayStart, java.time.LocalTime.of(8, 0))))
            hint = "8h"
        }
        val dayEnd = EditText(this).apply {
            setText(Logic.formatTime(Logic.parseTime(prefs.dayEnd, java.time.LocalTime.of(21, 0))))
            hint = "21h"
        }
        root.addView(row(text("Ma journée de ", 14f), dayStart, text(" à ", 14f), dayEnd))
        root.addView(button("Enregistrer ma journée") {
            prefs.dayStart = Logic.formatTime(Logic.parseTime(dayStart.text.toString(), java.time.LocalTime.of(8, 0)))
            prefs.dayEnd = Logic.formatTime(Logic.parseTime(dayEnd.text.toString(), java.time.LocalTime.of(21, 0)))
            toast("Journée : ${prefs.dayStart} – ${prefs.dayEnd}")
            Scheduler.schedule(this)
            render()
        })

        // Fiabilité
        section("Recevoir les rappels à l'heure")
        root.addView(text("Android endort les applis peu ouvertes : les rappels arrivent alors en retard, " +
            "puis tous d'un coup quand tu ouvres l'appli. Ces deux réglages l'en empêchent.", 13f, color = Color.GRAY))
        if (Scheduler.canExact(this)) root.addView(text("✅ Réveils à l'heure précise autorisés", 14f))
        else root.addView(button("Autoriser les réveils à l'heure précise") {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                android.net.Uri.parse("package:$packageName")))
        })
        if (Scheduler.ignoresBatteryLimits(this)) root.addView(text("✅ Pas de restriction de batterie", 14f))
        else root.addView(button("Ne pas restreindre la batterie pour Relance") {
            @android.annotation.SuppressLint("BatteryLife")
            val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName"))
            runCatching { startActivity(i) }.onFailure {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        })
        val fmt = { t: Long -> if (t == 0L) "—" else Logic.dayTime(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDateTime()) }
        root.addView(text("Dernière vérification : ${fmt(prefs.lastRun)}\nProchain réveil : ${fmt(prefs.nextWake)}",
            13f, color = Color.GRAY))

        // Réglages
        section("Après les événements")
        root.addView(check("Me demander si c'est fait à la fin de chaque événement", prefs.askEvents) { prefs.askEvents = it })
        root.addView(check("Si ce n'est pas fait, créer une tâche Google Tasks pour aujourd'hui",
            prefs.createTaskWhenNotDone) { prefs.createTaskWhenNotDone = it })
        root.addView(text("Agendas surveillés", 15f, bold = true))
        val cals = CalendarRepo.calendars(this)
        val watched = CalendarRepo.watchedIds(this, prefs)
        if (cals.isEmpty()) root.addView(text("Aucun agenda trouvé (autorisation manquante ?)", 14f, color = Color.GRAY))
        cals.forEach { cal ->
            root.addView(check("${cal.name}  ·  ${cal.account}", cal.id in watched) { on ->
                val cur = CalendarRepo.watchedIds(this, prefs).map { it.toString() }.toMutableSet()
                if (on) cur += cal.id.toString() else cur -= cal.id.toString()
                prefs.chosenCalendars = cur
            })
        }

        section("Relances des tâches")
        root.addView(check("Me relancer sur mes tâches", prefs.remindTasks) { prefs.remindTasks = it })
        root.addView(text("Heures des relances", 14f))
        val times = EditText(this).apply {
            setText(Logic.formatTimes(Logic.parseTimes(prefs.reminderTimes)))
            hint = "ex. 9h, 13h30, 18h"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(times)
        root.addView(text("Montrer les tâches à venir sur combien de jours", 14f))
        val horizon = EditText(this).apply {
            setText(prefs.horizonDays.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(horizon)
        root.addView(check("Me rappeler aussi quelques tâches sans date", prefs.remindUndated) { prefs.remindUndated = it })
        root.addView(button("Enregistrer") {
            val parsed = Logic.parseTimes(times.text.toString())
            prefs.reminderTimes = Logic.formatTimes(parsed)
            prefs.horizonDays = (horizon.text.toString().toIntOrNull() ?: 7).coerceIn(1, 60)
            // Le prochain horaire compte à partir de maintenant.
            prefs.lastSlotHandled = System.currentTimeMillis()
            Scheduler.schedule(this)
            toast("Relances à ${prefs.reminderTimes}")
            render()
        })

        // Historique
        val history = prefs.history()
        if (history.isNotEmpty()) {
            section("Derniers bilans")
            history.take(15).forEach { a ->
                val whenTxt = Logic.dayTime(Instant.ofEpochMilli(a.end).atZone(ZoneId.systemDefault()).toLocalDateTime())
                val status = when {
                    a.done && a.ticked -> "✅ fait · coché dans l'agenda"
                    a.done -> "✅ fait"
                    a.taskCreated -> "↻ pas fait · tâche créée"
                    else -> "❌ pas fait"
                }
                root.addView(text("${a.title}\n$status · $whenTxt", 14f).apply { setPadding(0, dp(6), 0, dp(6)) })
            }
        }
    }

    // ---------------- Tâches ----------------

    private fun loadTasks() {
        val box = tasksBox ?: return
        box.addView(text("Chargement des tâches…", 14f, color = Color.GRAY))
        io.execute {
            val result = runCatching {
                val api = GoogleAuth.api(this)
                val lists = api.lists()
                lists to lists.flatMap { (id, title) -> api.openTasks(id, title) }
            }
            main.post { if (box == tasksBox) showTasks(box, result) }
        }
    }

    private fun showTasks(box: LinearLayout, result: kotlin.Result<Pair<List<Pair<String, String>>, List<GTask>>>) {
        box.removeAllViews()
        val (lists, all) = result.getOrElse { e ->
            if (e is NeedsAuthException) box.addView(button("Reconnecter Google Tasks") { connect() })
            else box.addView(text("Impossible de charger les tâches : ${e.message}", 14f, color = Color.RED))
            return
        }
        // Choix des listes (catégories) à rappeler
        box.addView(text("Listes à me rappeler", 15f, bold = true).apply { setPadding(0, dp(10), 0, 0) })
        lists.forEach { (id, title) ->
            val chosen = prefs.chosenLists
            box.addView(check(title, chosen == null || id in chosen) { on ->
                val cur = (prefs.chosenLists ?: lists.map { it.first }.toSet()).toMutableSet()
                if (on) cur += id else cur -= id
                prefs.chosenLists = cur
                showTasks(box, result)
            })
        }
        val tasks = Checks.inChosenLists(prefs, all)
        val today = LocalDate.now()
        val groups = tasks.groupBy { Logic.bucket(it.due, today, prefs.horizonDays) }
        fun group(title: String, b: Logic.Bucket, color: Int) {
            val list = groups[b].orEmpty().sortedBy { it.due }
            if (list.isEmpty()) return
            box.addView(text("$title (${list.size})", 15f, bold = true, color = color).apply { setPadding(0, dp(10), 0, 0) })
            list.take(20).forEach { box.addView(taskRow(it, today)) }
        }
        group("En retard", Logic.Bucket.OVERDUE, Color.rgb(198, 40, 40))
        group("Aujourd'hui", Logic.Bucket.TODAY, ACCENT)
        group("À venir", Logic.Bucket.UPCOMING, Color.DKGRAY)
        if (tasks.none { Logic.bucket(it.due, today, prefs.horizonDays) in
                setOf(Logic.Bucket.OVERDUE, Logic.Bucket.TODAY, Logic.Bucket.UPCOMING) }) {
            box.addView(text("Rien en retard ni à venir. 👌", 14f))
        }
    }

    /** Un événement terminé sans réponse, avec les mêmes boutons que la notification. */
    private fun eventRow(q: Question): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        val ended = Logic.dayTime(Instant.ofEpochMilli(q.end).atZone(ZoneId.systemDefault()).toLocalDateTime())
        col.addView(text(q.title, 15f))
        col.addView(text("Terminé $ended", 12f, color = Color.GRAY))
        fun answer(action: String) {
            ActionReceiver().handle(this, ActionReceiver.eventIntent(action, q))
            Notifier.cancel(this, Notifier.eventNotifId(q.key))
            Notifier.tidyGroups(this)
            render()
        }
        val notDone = if (prefs.tasksConnected && prefs.createTaskWhenNotDone) "Pas fait → tâche" else "Pas fait"
        col.addView(row(
            small("C'est fait") { answer(ActionReceiver.EVENT_DONE) },
            small(notDone) { answer(ActionReceiver.EVENT_NOT_DONE) },
        ))
        return col
    }

    private fun taskRow(t: GTask, today: LocalDate): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        val due = t.due?.let { "${Logic.day(it)} (${Logic.relative(it, today)})" } ?: "sans date"
        col.addView(text(t.title, 15f))
        col.addView(text("$due · ${t.listTitle}", 12f, color = Color.GRAY))
        col.addView(row(
            small("Fait") { taskAction(t) { api -> api.complete(t.listId, t.id); Sync.taskCompleted(this, t.id) } },
            small("Demain") { taskAction(t) { api -> api.setDue(t.listId, t.id, today.plusDays(1)) } },
            small("+1 sem.") { taskAction(t) { api -> api.setDue(t.listId, t.id, today.plusDays(7)) } },
        ))
        return col
    }

    private fun taskAction(t: GTask, op: (TasksApi) -> Unit) {
        io.execute {
            val r = runCatching { op(GoogleAuth.api(this)) }
            main.post {
                r.exceptionOrNull()?.let { toast("Échec : ${it.message}") }
                Notifier.cancel(this, Notifier.taskNotifId(t.id))
                render()
            }
        }
    }

    private fun runRemind() {
        io.execute {
            val r = runCatching { Checks.remindTasks(this) }
            main.post {
                when (val e = r.exceptionOrNull()) {
                    null -> toast("Relance envoyée")
                    is NeedsAuthException -> connect()
                    else -> toast("Échec : ${e.message}")
                }
            }
        }
    }

    // ---------------- Connexion Google ----------------

    private fun connect() {
        Identity.getAuthorizationClient(this).authorize(GoogleAuth.request(null))
            .addOnSuccessListener { res ->
                if (res.hasResolution()) {
                    try {
                        startIntentSenderForResult(res.pendingIntent!!.intentSender, RC_AUTH, null, 0, 0, 0)
                    } catch (e: Exception) {
                        toast("Connexion impossible : ${e.message}")
                    }
                } else onAuthorized(res)
            }
            .addOnFailureListener { showAuthError(it) }
    }

    @Deprecated("API Activity classique")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_AUTH) return
        try {
            onAuthorized(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data))
        } catch (e: Exception) {
            showAuthError(e)
        }
    }

    private fun onAuthorized(res: AuthorizationResult) {
        @Suppress("DEPRECATION")
        prefs.account = runCatching { res.toGoogleSignInAccount()?.email }.getOrNull()
        prefs.tasksConnected = true
        Notifier.cancelAuth(this)
        toast("Google Tasks connecté")
        render()
    }

    private fun showAuthError(e: Exception) {
        val code = (e as? ApiException)?.statusCode
        val msg = if (code == 10) {
            "Google refuse la connexion : l'appli n'est pas (encore) déclarée dans Google Cloud.\n\n" +
                "Suis les étapes du README avec :\nNom du package : $packageName\nSHA-1 : ${sha1()}"
        } else if (code == 16 || code == 12501) {
            "Connexion annulée."
        } else "Connexion impossible : ${e.message}"
        AlertDialog.Builder(this).setTitle("Google Tasks").setMessage(msg).setPositiveButton("OK", null).show()
    }

    // ---------------- Divers ----------------

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun missingPermissions(): Array<String> {
        val wanted = mutableListOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        if (android.os.Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        return wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }

    private fun sha1(): String = runCatching {
        val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo!!.apkContentsSigners[0].toByteArray()
        MessageDigest.getInstance("SHA-1").digest(cert).joinToString(":") { "%02X".format(it) }
    }.getOrDefault("?")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun section(title: String) {
        root.addView(text(title, 18f, bold = true, color = ACCENT).apply { setPadding(0, dp(22), 0, dp(6)) })
    }

    private fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.BLACK, mono: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            setTextIsSelectable(mono)
            typeface = when {
                mono -> Typeface.MONOSPACE
                bold -> Typeface.DEFAULT_BOLD
                else -> Typeface.DEFAULT
            }
            setPadding(0, dp(2), 0, dp(2))
        }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun small(label: String, onClick: () -> Unit) = button(label, onClick).apply {
        textSize = 13f
        minHeight = 0; minimumHeight = 0
        minWidth = 0; minimumWidth = 0
    }

    private fun check(label: String, value: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label
        textSize = 14f
        isChecked = value
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }) }
    }
}
