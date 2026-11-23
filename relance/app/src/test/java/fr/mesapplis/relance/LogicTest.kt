package fr.mesapplis.relance

import fr.mesapplis.relance.Logic.Bucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class LogicTest {

    @Test
    fun parseTimes() {
        assertEquals(listOf(LocalTime.of(9, 0), LocalTime.of(18, 0)), Logic.parseTimes("18h, 9h"))
        assertEquals(listOf(LocalTime.of(8, 30), LocalTime.of(13, 0)), Logic.parseTimes("8h30 ; 13:00"))
        assertEquals(listOf(LocalTime.of(9, 0)), Logic.parseTimes(""))
        assertEquals(listOf(LocalTime.of(9, 0)), Logic.parseTimes("99h"))
        assertEquals("8h30, 18h", Logic.formatTimes(Logic.parseTimes("18h 8h30")))
    }

    @Test
    fun lastSlot() {
        val times = listOf(LocalTime.of(9, 0), LocalTime.of(18, 0))
        val day = LocalDate.of(2026, 11, 23)
        assertEquals(day.atTime(9, 0), Logic.lastSlot(day.atTime(12, 0), times))
        assertEquals(day.atTime(18, 0), Logic.lastSlot(day.atTime(18, 0), times))
        assertEquals(day.minusDays(1).atTime(18, 0), Logic.lastSlot(day.atTime(7, 0), times))
    }

    @Test
    fun buckets() {
        val today = LocalDate.of(2026, 11, 23)
        assertEquals(Bucket.OVERDUE, Logic.bucket(today.minusDays(1), today, 7))
        assertEquals(Bucket.TODAY, Logic.bucket(today, today, 7))
        assertEquals(Bucket.UPCOMING, Logic.bucket(today.plusDays(7), today, 7))
        assertEquals(Bucket.LATER, Logic.bucket(today.plusDays(8), today, 7))
        assertEquals(Bucket.UNDATED, Logic.bucket(null, today, 7))
    }

    @Test
    fun dues() {
        assertEquals(LocalDate.of(2026, 11, 24), Logic.parseDue("2026-11-24T00:00:00.000Z"))
        assertNull(Logic.parseDue(null))
        assertNull(Logic.parseDue("n'importe"))
        assertEquals("2026-11-24T00:00:00.000Z", Logic.formatDue(LocalDate.of(2026, 11, 24)))
    }

    @Test
    fun relative() {
        val today = LocalDate.of(2026, 11, 23)
        assertEquals("demain", Logic.relative(today.plusDays(1), today))
        assertEquals("il y a 4 jours", Logic.relative(today.minusDays(4), today))
    }

    @Test
    fun day() {
        val s = LocalTime.of(8, 0)
        val e = LocalTime.of(21, 0)
        assertEquals(true, Logic.inDay(LocalTime.of(8, 0), s, e))
        assertEquals(true, Logic.inDay(LocalTime.of(20, 59), s, e))
        assertEquals(false, Logic.inDay(LocalTime.of(21, 0), s, e))
        assertEquals(false, Logic.inDay(LocalTime.of(3, 0), s, e))
        // Plage qui passe minuit (ex. 22h → 2h)
        assertEquals(true, Logic.inDay(LocalTime.of(1, 0), LocalTime.of(22, 0), LocalTime.of(2, 0)))
        assertEquals(false, Logic.inDay(LocalTime.of(12, 0), LocalTime.of(22, 0), LocalTime.of(2, 0)))
        assertEquals(LocalTime.of(21, 30), Logic.parseTime("21h30", s))
        assertEquals(s, Logic.parseTime("", s))
    }

    @Test
    fun rotate() {
        val items = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("a", "b", "c"), Logic.rotate(items, 0, 3))
        assertEquals(listOf("d", "e", "a"), Logic.rotate(items, 1, 3))
        assertEquals(listOf("a"), Logic.rotate(listOf("a"), 7, 3))
        assertEquals(emptyList<String>(), Logic.rotate(emptyList<String>(), 2, 3))
    }
}
