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

    private const val MAX_LOOKBACK = 2 * 24 * 3600_000L

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
            else -> maxOf(prefs.lastCalendarCheck, now - MAX_LOOKBACK)
        }
        val waiting = prefs.snoozed().toMutableList()
        val waitingKeys = waiting.map { it.key }.toSet()
        CalendarRepo.endedBetween(ctx, from, now, CalendarRepo.watchedIds(ctx, prefs))
            .filter { it.key !in answered && it.key !in waitingKeys }
            .forEach { waiting += it.copy(askAt = it.end) }
        if (!manual) prefs.lastCalendarCheck = now
        // Une question restée en attente plus de 3 jours n'a plus de sens.
        waiting.removeAll { now - it.end > 3 * 24 * 3600_000L }

        if (!manual && busyReason(ctx, now) != null) {
            prefs.setSnoozed(waiting)
            return 0
        }
        val (due, later) = waiting.partition { it.askAt <= now || manual }
        prefs.setSnoozed(later)
        val toAsk = due.filter { it.key !in answered }
        toAsk.forEach { Notifier.askEvent(ctx, it) }
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
        val tasks = GoogleAuth.api(ctx).allOpenTasks()
        Notifier.cancelAuth(ctx)
        val undated = if (prefs.remindUndated) {
            val rot = prefs.rotation
            prefs.rotation = rot + 1
            Logic.rotate(tasks.filter { it.due == null }, rot, 3)
        } else emptyList()
        Notifier.remindTasks(ctx, tasks, prefs.horizonDays, undated)
        prefs.lastSlotHandled = System.currentTimeMillis()
        return tasks
    }

    fun schedule(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "relance-check", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CheckWorker>(15, TimeUnit.MINUTES).build(),
        )
    }
}

class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        if (prefs.askEvents) runCatching { Checks.checkEvents(ctx) }
        if (prefs.remindTasks && prefs.tasksConnected && Checks.slotDue(prefs) && Checks.busyReason(ctx) == null) {
            try {
                Checks.remindTasks(ctx)
            } catch (e: NeedsAuthException) {
                Notifier.needsAuth(ctx)
                prefs.lastSlotHandled = System.currentTimeMillis()
            } catch (e: Exception) {
                // Pas de réseau, etc. : on réessaiera au prochain passage (dans 15 min).
            }
        }
        return Result.success()
    }
}
