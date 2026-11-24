package fr.mesapplis.relance

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * Réveils précis de l'appli. Le passage toutes les 15 min de WorkManager peut être retardé
 * de plusieurs heures quand Android met l'appli en veille (puis tout arrive d'un coup quand on l'ouvre).
 * On programme donc une alarme exacte au prochain moment utile : fin du prochain événement,
 * prochaine heure de relance, début de la journée, question remise à plus tard… et au plus tard dans 30 min.
 */
object Scheduler {
    private const val ACTION_WAKE = "fr.mesapplis.relance.WAKE"
    private const val MAX_SLEEP = 30 * 60_000L

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun ignoresBatteryLimits(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Prochain moment où une vérification peut servir. */
    fun nextWake(ctx: Context, now: Long = System.currentTimeMillis()): Long {
        val prefs = Prefs(ctx)
        val zone = ZoneId.systemDefault()
        fun millis(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()
        val candidates = mutableListOf(now + MAX_SLEEP)

        // Fin du prochain événement (ou de celui en cours) : +1 min pour qu'il soit bien terminé.
        CalendarRepo.nextEnd(ctx, now, CalendarRepo.watchedIds(ctx, prefs))?.let { candidates += it + 60_000L }
        // Prochaine heure de relance des tâches.
        val today = LocalDate.now(zone)
        Logic.parseTimes(prefs.reminderTimes).forEach { t ->
            val at = millis(today.atTime(t)).let { if (it > now) it else millis(today.plusDays(1).atTime(t)) }
            candidates += at
        }
        // Début de la journée (questions en attente pendant la nuit).
        val start = Logic.parseTime(prefs.dayStart, java.time.LocalTime.of(8, 0))
        millis(today.atTime(start)).let { candidates += if (it > now) it else millis(today.plusDays(1).atTime(start)) }
        // Questions remises à plus tard.
        prefs.snoozed().map { it.askAt }.filter { it > now }.minOrNull()?.let { candidates += it }

        return maxOf(candidates.min(), now + 60_000L)
    }

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_WAKE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val at = runCatching { nextWake(ctx) }.getOrElse { System.currentTimeMillis() + MAX_SLEEP }
        if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        Prefs(ctx).nextWake = at
    }
}

/** Réveil programmé, redémarrage du téléphone, mise à jour de l'appli, changement d'heure. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        val app = ctx.applicationContext
        thread {
            try {
                Checks.runAll(app)
            } finally {
                runCatching { Scheduler.schedule(app) }
                pending.finish()
            }
        }
    }
}
