package fr.mesapplis.relance

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Toutes les notifications de l'appli. */
object Notifier {
    const val CH_EVENTS = "events"
    const val CH_TASKS = "tasks"
    const val CH_INFO = "info"
    private const val GROUP_TASKS = "fr.mesapplis.relance.TASKS"
    private const val GROUP_EVENTS = "fr.mesapplis.relance.EVENTS"
    private const val ID_EVENTS_SUMMARY = 5
    private const val ID_TASKS_SUMMARY = 1
    private const val ID_UPCOMING = 2
    private const val ID_AUTH = 3
    private const val ID_INFO = 4
    private const val MAX_TASK_NOTIFS = 5

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_EVENTS, "Bilan après un événement",
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = "« As-tu fait ce que tu avais à faire ? » à la fin de chaque événement"
        })
        nm.createNotificationChannel(NotificationChannel(CH_TASKS, "Relances de tâches",
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Tâches en retard, du jour et à venir"
        })
        nm.createNotificationChannel(NotificationChannel(CH_INFO, "Informations",
            NotificationManager.IMPORTANCE_LOW))
    }

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    fun eventNotifId(key: String) = 1000 + (key.hashCode() and 0x3fffffff)
    fun taskNotifId(taskId: String) = 1000 + ("t:$taskId".hashCode() and 0x3fffffff)

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(ctx: Context, label: String, intent: Intent, notifId: Int): Notification.Action {
        intent.setClass(ctx, ActionReceiver::class.java).putExtra(ActionReceiver.EXTRA_NOTIF_ID, notifId)
        val requestCode = (intent.action + notifId).hashCode()
        val pi = PendingIntent.getBroadcast(ctx, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null, label, pi).build()
    }

    // ---------- Après un événement ----------

    /**
     * Pose une ou plusieurs questions. Dès qu'il y en a plusieurs à l'écran, elles sont rangées
     * dans un seul paquet qui ne sonne qu'une fois.
     */
    fun askEvents(ctx: Context, questions: List<Question>) {
        if (questions.isEmpty()) return
        val nm = nm(ctx)
        val newIds = questions.map { eventNotifId(it.key) }.toSet()
        val others = nm.activeNotifications.filter {
            it.notification.channelId == CH_EVENTS && it.id != ID_EVENTS_SUMMARY && it.id !in newIds
        }
        val grouped = others.size + questions.size >= 2
        if (grouped) {
            // Les questions déjà affichées rejoignent le paquet, sans sonner à nouveau.
            others.forEach {
                val b = Notification.Builder.recoverBuilder(ctx, it.notification)
                    .setGroup(GROUP_EVENTS).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                    .setOnlyAlertOnce(true)
                nm.notify(it.id, b.build())
            }
        }
        questions.forEach { nm.notify(eventNotifId(it.key), eventNotif(ctx, it, grouped)) }
        if (grouped) {
            val titles = others.mapNotNull { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) } +
                questions.map { "✔︎ ${it.title} ?" }
            val count = titles.size
            val style = Notification.InboxStyle().setBigContentTitle("$count bilans à faire")
            titles.take(6).forEach { style.addLine(it) }
            nm.notify(ID_EVENTS_SUMMARY, Notification.Builder(ctx, CH_EVENTS)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("$count bilans à faire")
                .setContentText("As-tu fait ce que tu avais à faire ?")
                .setStyle(style)
                .setGroup(GROUP_EVENTS).setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
                .build())
        }
    }

    private fun eventNotif(ctx: Context, q: Question, grouped: Boolean): Notification {
        val id = eventNotifId(q.key)
        val ended = Logic.dayTime(Instant.ofEpochMilli(q.end).atZone(ZoneId.systemDefault()).toLocalDateTime())
        val big = buildString {
            append("As-tu fait ce que tu avais à faire ?")
            if (q.description.isNotEmpty()) append("\n\n").append(q.description.take(600))
            append("\n\nTerminé ").append(ended)
        }
        val prefs = Prefs(ctx)
        val notDoneLabel = if (prefs.tasksConnected && prefs.createTaskWhenNotDone) "Pas fait → tâche" else "Pas fait"
        val b = Notification.Builder(ctx, CH_EVENTS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("✔︎ ${q.title} ?")
            .setContentText("As-tu fait ce que tu avais à faire ?")
            .setStyle(Notification.BigTextStyle().bigText(big))
            .setContentIntent(openApp(ctx))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setWhen(q.end).setShowWhen(true)
            .setOnlyAlertOnce(true)
            .addAction(action(ctx, "C'est fait", ActionReceiver.eventIntent(ActionReceiver.EVENT_DONE, q), id))
            .addAction(action(ctx, notDoneLabel, ActionReceiver.eventIntent(ActionReceiver.EVENT_NOT_DONE, q), id))
            .addAction(action(ctx, "Dans 1 h", ActionReceiver.eventIntent(ActionReceiver.EVENT_SNOOZE, q), id))
        if (grouped) b.setGroup(GROUP_EVENTS).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        return b.build()
    }

    /** Retire le résumé d'un paquet quand il ne reste plus qu'une notification (ou aucune) dedans. */
    fun tidyGroups(ctx: Context) {
        val nm = nm(ctx)
        val active = nm.activeNotifications
        val events = active.count { it.notification.channelId == CH_EVENTS && it.id != ID_EVENTS_SUMMARY }
        if (events < 2) nm.cancel(ID_EVENTS_SUMMARY)
        val tasks = active.count { it.notification.channelId == CH_TASKS && it.id != ID_TASKS_SUMMARY }
        if (tasks == 0) nm.cancel(ID_TASKS_SUMMARY)
    }

    // ---------- Relance des tâches ----------

    /** Efface les anciennes relances de tâches (certaines ont pu être faites entre-temps). */
    fun clearTaskNotifs(ctx: Context) {
        val nm = nm(ctx)
        nm.activeNotifications.filter { it.notification.channelId == CH_TASKS }.forEach { nm.cancel(it.id) }
    }

    /**
     * Une relance = un seul paquet qui sonne une fois : les tâches en retard et du jour
     * (une notification chacune, avec boutons) et le récapitulatif « à venir ».
     */
    fun remindTasks(ctx: Context, tasks: List<GTask>, horizonDays: Int, undated: List<GTask>) {
        clearTaskNotifs(ctx)
        val today = LocalDate.now()
        val urgent = tasks.filter {
            val b = Logic.bucket(it.due, today, horizonDays)
            b == Logic.Bucket.OVERDUE || b == Logic.Bucket.TODAY
        }.sortedBy { it.due }
        val upcoming = tasks.filter { Logic.bucket(it.due, today, horizonDays) == Logic.Bucket.UPCOMING }
            .sortedBy { it.due }
        val hasUpcoming = upcoming.isNotEmpty() || undated.isNotEmpty()
        val shown = urgent.take(MAX_TASK_NOTIFS)
        val children = shown.size + (if (hasUpcoming) 1 else 0)
        if (children == 0) return
        val grouped = children >= 2

        shown.forEach { taskNotif(ctx, it, today, grouped) }
        if (hasUpcoming) upcomingNotif(ctx, upcoming, undated, grouped)

        if (grouped) {
            val late = urgent.count { it.due!!.isBefore(today) }
            val parts = listOfNotNull(
                if (late > 0) "$late en retard" else null,
                (urgent.size - late).takeIf { it > 0 }?.let { "$it pour aujourd'hui" },
                upcoming.size.takeIf { it > 0 }?.let { "$it à venir" },
            )
            val title = if (parts.isEmpty()) "Tes tâches" else parts.joinToString(" · ")
            val style = Notification.InboxStyle().setBigContentTitle(title)
            urgent.take(6).forEach { style.addLine(it.title) }
            if (urgent.size > MAX_TASK_NOTIFS) style.setSummaryText("+ ${urgent.size - MAX_TASK_NOTIFS} autres dans l'appli")
            nm(ctx).notify(ID_TASKS_SUMMARY, Notification.Builder(ctx, CH_TASKS)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setStyle(style)
                .setGroup(GROUP_TASKS).setGroupSummary(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
                .build())
        }
    }

    private fun upcomingNotif(ctx: Context, upcoming: List<GTask>, undated: List<GTask>, grouped: Boolean) {
        val style = Notification.InboxStyle()
        upcoming.take(7).forEach { style.addLine("${Logic.day(it.due!!)} · ${it.title}") }
        undated.forEach { style.addLine("💭 ${it.title}") }
        val title = if (upcoming.isNotEmpty()) "À venir : ${upcoming.size} tâche${if (upcoming.size > 1) "s" else ""}"
        else "Pense aussi à…"
        val text = (upcoming.map { it.title } + undated.map { it.title }).joinToString(", ")
        style.setBigContentTitle(title)
        if (undated.isNotEmpty() && upcoming.isNotEmpty()) style.setSummaryText("💭 = tâche sans date")
        val b = Notification.Builder(ctx, CH_TASKS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
        if (grouped) b.setGroup(GROUP_TASKS).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        nm(ctx).notify(ID_UPCOMING, b.build())
    }

    private fun taskNotif(ctx: Context, t: GTask, today: LocalDate, grouped: Boolean) {
        val id = taskNotifId(t.id)
        val late = t.due!!.isBefore(today)
        val header = if (late) "En retard · prévu ${Logic.relative(t.due, today)}" else "Pour aujourd'hui"
        val big = buildString {
            append(t.title)
            if (t.notes.isNotEmpty()) append("\n\n").append(t.notes.take(500))
            append("\n\nListe : ").append(t.listTitle)
        }
        val b = Notification.Builder(ctx, CH_TASKS)
            .setSmallIcon(R.drawable.ic_notif)
            .setSubText(header)
            .setContentTitle(t.title)
            .setContentText(header)
            .setStyle(Notification.BigTextStyle().bigText(big))
            .setContentIntent(openApp(ctx))
            .addAction(action(ctx, "C'est fait", ActionReceiver.taskIntent(ActionReceiver.TASK_DONE, t), id))
            .addAction(action(ctx, "Demain", ActionReceiver.taskIntent(ActionReceiver.TASK_TOMORROW, t), id))
            .addAction(action(ctx, "Dans 1 semaine", ActionReceiver.taskIntent(ActionReceiver.TASK_NEXT_WEEK, t), id))
        if (grouped) b.setGroup(GROUP_TASKS).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        nm(ctx).notify(id, b.build())
    }

    // ---------- Divers ----------

    fun needsAuth(ctx: Context) {
        nm(ctx).notify(ID_AUTH, Notification.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Reconnecte Google Tasks")
            .setContentText("Ouvre Relance et touche « Se connecter à Google Tasks ».")
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build())
    }

    fun info(ctx: Context, title: String, text: String) {
        nm(ctx).notify(ID_INFO, Notification.Builder(ctx, CH_INFO)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build())
    }

    fun cancel(ctx: Context, id: Int) = nm(ctx).cancel(id)

    fun cancelAuth(ctx: Context) = nm(ctx).cancel(ID_AUTH)
}
