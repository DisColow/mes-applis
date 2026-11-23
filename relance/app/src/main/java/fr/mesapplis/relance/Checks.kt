package fr.mesapplis.relance

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Les deux vérifications de l'appli, appelées toutes les 15 min en arrière-plan ou à la demande. */
object Checks {

    private const val MAX_LOOKBACK = 2 * 24 * 3600_000L

    /**
     * Pose la question pour chaque événement terminé depuis la dernière vérification,
     * et repose celles remises à plus tard. Renvoie le nombre de questions posées.
     * [manualHours] > 0 : vérification manuelle des N dernières heures (sans les événements déjà répondus).
     */
    fun checkEvents(ctx: Context, now: Long = System.currentTimeMillis(), manualHours: Int = 0): Int {
        val prefs = Prefs(ctx)
        val answered = prefs.history().map { it.key }.toSet()
        var asked = 0

        val from = when {
            manualHours > 0 -> now - manualHours * 3600_000L
            prefs.lastCalendarCheck == 0L -> now - 30 * 60_000L
            else -> maxOf(prefs.lastCalendarCheck, now - MAX_LOOKBACK)
        }
        val snoozed = prefs.snoozed()
        val snoozedKeys = snoozed.map { it.key }.toSet()
        CalendarRepo.endedBetween(ctx, from, now, CalendarRepo.watchedIds(ctx, prefs))
            .filter { it.key !in answered && it.key !in snoozedKeys }
            .forEach { Notifier.askEvent(ctx, it); asked++ }
        if (manualHours == 0) prefs.lastCalendarCheck = now

        val (due, later) = snoozed.partition { it.askAt <= now || manualHours > 0 }
        if (due.isNotEmpty()) {
            prefs.setSnoozed(later)
            due.filter { it.key !in answered }.forEach { Notifier.askEvent(ctx, it); asked++ }
        }
        return asked
    }

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

    fun today(): LocalDate = LocalDate.now()
}

class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        if (prefs.askEvents) runCatching { Checks.checkEvents(ctx) }
        if (prefs.remindTasks && prefs.tasksConnected && Checks.slotDue(prefs)) {
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
