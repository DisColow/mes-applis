package fr.mesapplis.relance

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances

data class CalendarInfo(val id: Long, val name: String, val account: String, val writable: Boolean)

/** Lecture de l'agenda du téléphone (synchronisé avec Google Agenda), sans connexion internet. */
object CalendarRepo {

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(ctx: Context): List<CalendarInfo> {
        if (!hasPermission(ctx)) return emptyList()
        val out = mutableListOf<CalendarInfo>()
        ctx.contentResolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME,
                Calendars.CALENDAR_ACCESS_LEVEL),
            "${Calendars.VISIBLE} = 1", null, Calendars.CALENDAR_DISPLAY_NAME,
        )?.use { c ->
            while (c.moveToNext()) {
                out += CalendarInfo(
                    c.getLong(0), c.getString(1) ?: "?", c.getString(2) ?: "",
                    c.getInt(3) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                )
            }
        }
        return out
    }

    /**
     * Agendas surveillés : le choix de l'utilisateur, sinon ceux qu'il peut modifier
     * (on écarte ainsi les jours fériés, anniversaires et agendas partagés en lecture seule).
     */
    fun watchedIds(ctx: Context, prefs: Prefs): Set<Long> =
        prefs.chosenCalendars?.mapNotNull { it.toLongOrNull() }?.toSet()
            ?: calendars(ctx).filter { it.writable }.map { it.id }.toSet()

    /** Événements (hors journée entière, refusés ou annulés) terminés dans ]from, to]. */
    fun endedBetween(ctx: Context, from: Long, to: Long, calendarIds: Set<Long>): List<Question> {
        if (!hasPermission(ctx) || calendarIds.isEmpty()) return emptyList()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from - 3 * 24 * 3600_000L)
            ContentUris.appendId(it, to)
        }.build()
        val out = mutableListOf<Question>()
        ctx.contentResolver.query(
            uri,
            arrayOf(Instances.EVENT_ID, Instances.TITLE, Instances.DESCRIPTION, Instances.BEGIN,
                Instances.END, Instances.CALENDAR_ID, Instances.SELF_ATTENDEE_STATUS, Instances.STATUS),
            "${Instances.END} > ? AND ${Instances.END} <= ? AND ${Instances.ALL_DAY} = 0",
            arrayOf(from.toString(), to.toString()),
            Instances.END,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getLong(5) !in calendarIds) continue
                if (c.getInt(6) == Attendees.ATTENDEE_STATUS_DECLINED) continue
                if (!c.isNull(7) && c.getInt(7) == Events.STATUS_CANCELED) continue
                val begin = c.getLong(3)
                out += Question(
                    key = "${c.getLong(0)}@$begin",
                    title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "(Sans titre)",
                    description = c.getString(2)?.trim() ?: "",
                    begin = begin,
                    end = c.getLong(4),
                )
            }
        }
        return out
    }
}
