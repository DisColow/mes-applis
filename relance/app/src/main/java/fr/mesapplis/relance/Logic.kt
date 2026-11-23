package fr.mesapplis.relance

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Logique pure (sans Android) : horaires de relance, classement des tâches, dates. */
object Logic {

    /** Lit « 9h, 13h30, 18:00 » → [09:00, 13:30, 18:00]. Vide ou illisible → 9h. */
    fun parseTimes(text: String): List<LocalTime> {
        val regex = Regex("""(\d{1,2})\s*(?:[hH:]\s*(\d{2})?)?""")
        val times = regex.findAll(text).mapNotNull { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
        }.distinct().sorted().toList()
        return times.ifEmpty { listOf(LocalTime.of(9, 0)) }
    }

    fun formatTimes(times: List<LocalTime>): String =
        times.joinToString(", ") { if (it.minute == 0) "${it.hour}h" else "${it.hour}h%02d".format(it.minute) }

    /** Coche ajoutée au titre d'un événement fait. */
    const val DONE_MARK = "✅"

    fun isMarkedDone(title: String) = title.trimStart().startsWith(DONE_MARK)

    fun markDone(title: String) = if (isMarkedDone(title)) title else "$DONE_MARK $title"

    /**
     * Forme comparable d'un titre (pour retrouver la tâche d'un événement) :
     * sans coche, accents, ponctuation ni majuscules. « ✅ Appeler l'école ! » → « appeler l ecole ».
     */
    fun normalizeTitle(title: String): String =
        java.text.Normalizer.normalize(title.replace(DONE_MARK, ""), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    /** Lit une seule heure (« 8h », « 21h30 »), ou [default] si illisible. */
    fun parseTime(text: String, default: LocalTime): LocalTime =
        if (Regex("""\d""").containsMatchIn(text)) parseTimes(text).first() else default

    fun formatTime(t: LocalTime): String = formatTimes(listOf(t))

    /** Vrai si [t] est dans la journée [start, end[ (une plage qui passe minuit est acceptée). */
    fun inDay(t: LocalTime, start: LocalTime, end: LocalTime): Boolean =
        if (start <= end) t >= start && t < end else t >= start || t < end

    /** Le dernier créneau de relance passé (aujourd'hui ou hier), au plus tard [now]. */
    fun lastSlot(now: LocalDateTime, times: List<LocalTime>): LocalDateTime {
        val today = now.toLocalDate()
        val passedToday = times.filter { !it.isAfter(now.toLocalTime()) }
        return if (passedToday.isNotEmpty()) today.atTime(passedToday.max())
        else today.minusDays(1).atTime(times.max())
    }

    enum class Bucket { OVERDUE, TODAY, UPCOMING, LATER, UNDATED }

    fun bucket(due: LocalDate?, today: LocalDate, horizonDays: Int): Bucket = when {
        due == null -> Bucket.UNDATED
        due.isBefore(today) -> Bucket.OVERDUE
        due == today -> Bucket.TODAY
        !due.isAfter(today.plusDays(horizonDays.toLong())) -> Bucket.UPCOMING
        else -> Bucket.LATER
    }

    /** Google Tasks renvoie l'échéance sous la forme « 2026-11-24T00:00:00.000Z » (seule la date compte). */
    fun parseDue(s: String?): LocalDate? =
        if (s.isNullOrBlank() || s.length < 10) null
        else runCatching { LocalDate.parse(s.substring(0, 10)) }.getOrNull()

    fun formatDue(d: LocalDate): String = "${d}T00:00:00.000Z"

    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH)
    private val dayTimeFmt = DateTimeFormatter.ofPattern("EEE d MMM 'à' HH'h'mm", Locale.FRENCH)

    fun day(d: LocalDate): String = d.format(dayFmt)
    fun dayTime(d: LocalDateTime): String = d.format(dayTimeFmt)

    /** « aujourd'hui », « demain », « dans 3 jours », « hier », « il y a 5 jours ». */
    fun relative(due: LocalDate, today: LocalDate): String {
        val n = java.time.temporal.ChronoUnit.DAYS.between(today, due)
        return when {
            n == 0L -> "aujourd'hui"
            n == 1L -> "demain"
            n == -1L -> "hier"
            n > 1 -> "dans $n jours"
            else -> "il y a ${-n} jours"
        }
    }

    /** Choisit [count] tâches sans date à rappeler, en tournant à chaque relance. */
    fun <T> rotate(items: List<T>, offset: Int, count: Int): List<T> {
        if (items.isEmpty()) return emptyList()
        val start = Math.floorMod(offset * count, items.size)
        return (0 until minOf(count, items.size)).map { items[(start + it) % items.size] }
    }
}
