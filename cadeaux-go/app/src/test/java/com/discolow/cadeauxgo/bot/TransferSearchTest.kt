package com.discolow.cadeauxgo.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferSearchTest {
    @Test
    fun recherche() {
        val s = TransferSearch.build(1500, 120, "Leveinard, ronflex ;; Ronflex")
        assertTrue(s.startsWith("0*,1*,2*&"))
        for (w in listOf("!chromatique", "!légendaire", "!fabuleux", "!dynamax", "!gigamax", "!favori", "!favoris", "!#", "pc-1500", "pv-120")) {
            assertTrue("$w dans $s", s.split('&').contains(w))
        }
        assertTrue(s.endsWith("&!leveinard&!ronflex"))
        assertEquals(1, Regex("!ronflex").findAll(s).count())
    }
}
