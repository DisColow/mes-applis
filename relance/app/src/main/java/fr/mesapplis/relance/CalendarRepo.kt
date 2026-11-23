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

    fun canWrite(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /**
     * Ajoute la coche ✅ au titre de l'événement (seulement cette occurrence s'il se répète).
     * Possible seulement si l'agenda est modifiable et que je peux modifier l'événement
     * (j'en suis l'organisateur, ou les invités ont le droit de le modifier).
     * Renvoie vrai si l'événement porte la coche à la fin.
     */
    fun markDone(ctx: Context, q: Question): Boolean {
        if (!canWrite(ctx)) return false
        val eventId = q.key.substringBefore("@").toLongOrNull() ?: return false
        return runCatching {
            ctx.contentResolver.query(
                ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
                arrayOf(Events.TITLE, Events.RRULE, Events.RDATE, Events.CALENDAR_ACCESS_LEVEL,
                    Events.IS_ORGANIZER, Events.GUESTS_CAN_MODIFY),
                null, null, null,
            )?.use { c ->
                if (!c.moveToFirst()) return@use false
                val title = c.getString(0) ?: ""
                if (Logic.isMarkedDone(title)) return@use true
                if (c.getInt(3) < Calendars.CAL_ACCESS_CONTRIBUTOR) return@use false
                val organizer = c.getString(4).let { it == null || it == "1" }
                if (!organizer && c.getInt(5) != 1) return@use false
                val values = android.content.ContentValues().apply { put(Events.TITLE, Logic.markDone(title)) }
                val recurring = !c.getString(1).isNullOrEmpty() || !c.getString(2).isNullOrEmpty()
                if (recurring) {
                    // Exception pour cette seule occurrence : les autres gardent leur titre.
                    values.put(Events.ORIGINAL_INSTANCE_TIME, q.begin)
                    ctx.contentResolver.insert(
                        ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, eventId), values) != null
                } else {
                    ctx.contentResolver.update(
                        ContentUris.withAppendedId(Events.CONTENT_URI, eventId), values, null, null) > 0
                }
            } ?: false
        }.getOrDefault(false)
    }

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

    /**
     * L'événement qui m'occupe en ce moment (ou qui commence dans moins de [marginMin] minutes),
     * hors journée entière, refusé, annulé ou marqué « disponible ». null = agenda libre.
     */
    fun busyWith(ctx: Context, now: Long, calendarIds: Set<Long>, marginMin: Int = 5): Question? {
        if (!hasPermission(ctx) || calendarIds.isEmpty()) return null
        val soon = now + marginMin * 60_000L
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - 24 * 3600_000L)
            ContentUris.appendId(it, soon)
        }.build()
        ctx.contentResolver.query(
            uri,
            arrayOf(Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.CALENDAR_ID,
                Instances.SELF_ATTENDEE_STATUS, Instances.STATUS, Instances.AVAILABILITY),
            "${Instances.BEGIN} < ? AND ${Instances.END} > ? AND ${Instances.ALL_DAY} = 0",
            arrayOf(soon.toString(), now.toString()),
            "${Instances.END} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getLong(4) !in calendarIds) continue
                if (c.getInt(5) == Attendees.ATTENDEE_STATUS_DECLINED) continue
                if (!c.isNull(6) && c.getInt(6) == Events.STATUS_CANCELED) continue
                if (c.getInt(7) == Events.AVAILABILITY_FREE) continue
                return Question("${c.getLong(0)}@${c.getLong(2)}", c.getString(1) ?: "(Sans titre)", "",
                    c.getLong(2), c.getLong(3))
            }
        }
        return null
    }

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
                // Déjà coché (dans l'appli ou à la main) : pas besoin de demander.
                if (Logic.isMarkedDone(c.getString(1) ?: "")) continue
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
