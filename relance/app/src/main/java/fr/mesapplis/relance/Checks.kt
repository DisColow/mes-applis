package fr.mesapplis.relance

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Les deux vérifications de l'appli, appelées toutes les 15 min en arrière-plan ou à la demande. */
object Checks {

    /**
     * Seuls les événements terminés depuis moins de 6 h donnent lieu à une notification :
     * après une longue coupure (téléphone éteint, appli mise en veille), on n'envoie pas
     * une rafale de vieilles questions. Les plus anciennes restent dans l'appli (« À faire le point »).
     */
    private const val RECENT = 6 * 3600_000L

    /** Peut-on me déranger maintenant ? null = oui, sinon la raison (affichée dans l'appli). */
    fun busyReason(ctx: Context, now: Long = System.currentTimeMillis()): String? {
        val prefs = Prefs(ctx)
        val start = Logic.parseTime(prefs.dayStart, LocalTime.of(8, 0))
        val end = Logic.parseTime(prefs.dayEnd, LocalTime.of(21, 0))
        val time = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalTime()
        if (!Logic.inDay(time, start, end))
            return "hors de ta journée (${Logic.formatTime(start)} – ${Logic.formatTime(end)})"
        if (prefs.waitUntilFree) {
            val ev = CalendarRepo.busyWith(ctx, now, CalendarRepo.watchedIds(ctx, prefs))
            if (ev != null) {
                val until = Instant.ofEpochMilli(ev.end).atZone(ZoneId.systemDefault()).toLocalTime()
                val what = if (ev.begin > now) "« ${ev.title} » commence bientôt" else "« ${ev.title} » est en cours"
                return "$what (jusqu'à ${Logic.formatTime(until)})"
            }
        }
        return null
    }

    /**
     * Repère les événements terminés depuis la dernière vérification et les met en attente ;
     * pose ensuite les questions en attente si on peut me déranger (en journée, agenda libre).
     * Renvoie le nombre de questions posées.
     * [manualHours] > 0 : vérification demandée depuis l'appli sur les N dernières heures,
     * posée tout de suite (sans les événements déjà répondus).
     */
    fun checkEvents(ctx: Context, now: Long = System.currentTimeMillis(), manualHours: Int = 0): Int {
        val prefs = Prefs(ctx)
        val answered = prefs.history().map { it.key }.toSet()
        val manual = manualHours > 0

        val from = when {
            manual -> now - manualHours * 3600_000L
            prefs.lastCalendarCheck == 0L -> now - 30 * 60_000L
            else -> maxOf(prefs.lastCalendarCheck, now - RECENT)
        }
        val waiting = prefs.snoozed().toMutableList()
        val waitingKeys = waiting.map { it.key }.toSet()
        CalendarRepo.endedBetween(ctx, from, now, CalendarRepo.watchedIds(ctx, prefs))
            .filter { it.key !in answered && it.key !in waitingKeys }
            .forEach { waiting += it.copy(askAt = it.end) }
        if (!manual) prefs.lastCalendarCheck = now
        // Une question en attente depuis trop longtemps n'est plus notifiée (elle reste dans l'appli).
        waiting.removeAll { now - maxOf(it.end, it.askAt) > 2 * RECENT }

        if (!manual && busyReason(ctx, now) != null) {
            prefs.setSnoozed(waiting)
            return 0
        }
        val (due, later) = waiting.partition { it.askAt <= now || manual }
        prefs.setSnoozed(later)
        val toAsk = due.filter { it.key !in answered }
        Notifier.askEvents(ctx, toAsk)
        return toAsk.size
    }

    /** Questions en attente du bon moment (ou remises à plus tard). */
    fun waitingCount(ctx: Context) = Prefs(ctx).snoozed().size

    /** Vrai si un horaire de relance est passé depuis la dernière relance. */
    fun slotDue(prefs: Prefs, now: LocalDateTime = LocalDateTime.now()): Boolean {
        val slot = Logic.lastSlot(now, Logic.parseTimes(prefs.reminderTimes))
        val slotMillis = slot.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return prefs.lastSlotHandled < slotMillis
    }

    /** Récupère les tâches Google Tasks et envoie les relances. Renvoie les tâches ouvertes. */
    fun remindTasks(ctx: Context): List<GTask> {
        val prefs = Prefs(ctx)
        val api = GoogleAuth.api(ctx)
        val all = api.allOpenTasks()
        Notifier.cancelAuth(ctx)
        Sync.checkLinkedTasks(ctx, api, all)
        val tasks = inChosenLists(prefs, all)
        val undated = if (prefs.remindUndated) {
            val rot = prefs.rotation
            prefs.rotation = rot + 1
            Logic.rotate(tasks.filter { it.due == null }, rot, 3)
        } else emptyList()
        Notifier.remindTasks(ctx, tasks, prefs.horizonDays, undated)
        prefs.lastSlotHandled = System.currentTimeMillis()
        return tasks
    }

    /** Tâches des listes choisies pour les relances. */
    fun inChosenLists(prefs: Prefs, tasks: List<GTask>): List<GTask> {
        val chosen = prefs.chosenLists ?: return tasks
        return tasks.filter { it.listId in chosen }
    }

    /** Une vérification complète (événements + relance des tâches si c'est l'heure). */
    @Synchronized
    fun runAll(ctx: Context) {
        val prefs = Prefs(ctx)
        prefs.lastRun = System.currentTimeMillis()
        if (prefs.askEvents) runCatching { checkEvents(ctx) }
        if (prefs.remindTasks && prefs.tasksConnected && slotDue(prefs) && busyReason(ctx) == null) {
            try {
                remindTasks(ctx)
            } catch (e: NeedsAuthException) {
                Notifier.needsAuth(ctx)
                prefs.lastSlotHandled = System.currentTimeMillis()
            } catch (e: Exception) {
                // Pas de réseau, etc. : on réessaiera au prochain réveil.
            }
        }
    }

    fun schedule(ctx: Context) {
        Scheduler.schedule(ctx)
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "relance-check", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CheckWorker>(15, TimeUnit.MINUTES).build(),
        )
    }
}

/** Filet de sécurité toutes les 15 min, en plus des réveils précis. */
class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Checks.runAll(applicationContext)
        runCatching { Scheduler.schedule(applicationContext) }
        return Result.success()
    }
}

/** Garde l'agenda et Google Tasks d'accord quand quelque chose est fait. */
object Sync {

    /** « C'est fait » sur un événement : coche dans l'agenda et réponse enregistrée. */
    fun eventDone(ctx: Context, q: Question) {
        val ticked = CalendarRepo.markDone(ctx, q)
        Prefs(ctx).addAnswer(Answer(q.key, q.title, q.end, done = true, taskCreated = false, ticked = ticked))
    }

    /**
     * Termine dans Google Tasks les tâches qui correspondent à l'événement fait :
     * celle créée après un « Pas fait », et celles qui portent le même titre.
     */
    fun completeTasksOf(ctx: Context, api: TasksApi, q: Question): Int {
        val prefs = Prefs(ctx)
        val linked = prefs.taskLinks().filterValues { it.key == q.key }.keys
        val wanted = Logic.normalizeTitle(q.title)
        val matches = api.allOpenTasks().filter {
            it.id in linked || (wanted.isNotEmpty() && Logic.normalizeTitle(it.title) == wanted)
        }
        matches.forEach {
            api.complete(it.listId, it.id)
            prefs.unlinkTask(it.id)
            Notifier.cancel(ctx, Notifier.taskNotifId(it.id))
        }
        linked.forEach { prefs.unlinkTask(it) }
        return matches.size
    }

    /** Une tâche vient d'être terminée : si elle venait d'un événement « pas fait », on coche l'événement. */
    fun taskCompleted(ctx: Context, taskId: String) {
        val prefs = Prefs(ctx)
        val q = prefs.taskLinks()[taskId] ?: return
        prefs.unlinkTask(taskId)
        val ticked = CalendarRepo.markDone(ctx, q)
        prefs.addAnswer(Answer(q.key, q.title, q.end, done = true, taskCreated = true, ticked = ticked))
    }

    /** Tâches liées à un événement, terminées directement dans Google Tasks depuis la dernière fois. */
    fun checkLinkedTasks(ctx: Context, api: TasksApi, open: List<GTask>) {
        val openIds = open.map { it.id }.toSet()
        Prefs(ctx).taskLinks().keys.filter { it !in openIds }.forEach { id ->
            when (runCatching { api.status(id) }.getOrElse { return@forEach }) {
                "completed" -> taskCompleted(ctx, id)
                null -> Prefs(ctx).unlinkTask(id) // supprimée
            }
        }
    }
}
