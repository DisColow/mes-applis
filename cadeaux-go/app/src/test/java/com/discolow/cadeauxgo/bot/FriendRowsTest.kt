package com.discolow.cadeauxgo.bot

import com.discolow.cadeauxgo.bot.FriendRows.Box
import org.junit.Assert.assertEquals
import org.junit.Test

/** Reprend la capture d'écran réelle de la liste d'amis (924×2000), réduite à 480 px de large. */
class FriendRowsTest {
    private fun s(v: Int) = v * 480 / 924
    private fun box(x: Int, y: Int, w: Int, h: Int) = Box(s(x), s(y), s(w), s(h))

    /** Les 5 cœurs d'une ligne dont la rangée de cœurs est à [y] (capture d'origine). */
    private fun hearts(y: Int) = (0 until 5).map { box(64 + it * 30, y - 13, 26, 26) }

    @Test
    fun captureReelle() {
        val heartsY = listOf(330, 625, 920, 1215, 1510, 1805)
        val hearts = heartsY.flatMap { hearts(it) }
        // Paquets cadeau des lignes 1 à 3 (ami 4, ami 5, ami 6).
        val gifts = listOf(295, 590, 885).map { box(280, it - 55, 100, 110) }
        // Flèches grises « déjà envoyé » des lignes 4 à 6.
        val sent = listOf(1163, 1458, 1753).map { box(172, it - 20, 40, 40) }

        val rows = FriendRows.analyze(hearts, gifts, sent, frameH = s(2000))
        // Ligne 1 (cœurs à 16,5 % de la hauteur) gardée ; ligne 6 (90 %) sous les boutons flottants : écartée.
        assertEquals(5, rows.size)
        assertEquals(listOf(true, true, true, false, false), rows.map { it.hasGift })
        assertEquals(listOf(false, false, false, true, true), rows.map { it.alreadySent })
        assertEquals(listOf(true, true, true, false, false), rows.map { it.needsVisit(open = true, send = true) })
        // Envoi seul : les 3 premiers n'ont pas de flèche → à visiter.
        assertEquals(listOf(true, true, true, false, false), rows.map { it.needsVisit(open = false, send = true) })
        // Repère = cœur le plus à gauche.
        assertEquals(s(64), rows[0].x)
    }

    @Test
    fun ouvertureSeule() {
        val hearts = listOf(700, 1000, 1300).flatMap { hearts(it) }
        val gifts = listOf(box(280, 1250, 100, 110))
        val sent = listOf(box(172, 650, 40, 40), box(172, 950, 40, 40))
        val rows = FriendRows.analyze(hearts, gifts, sent, frameH = s(2000))
        assertEquals(listOf(false, false, true), rows.map { it.needsVisit(open = true, send = false) })
        // Troisième ami : cadeau reçu ET pas encore de flèche → visité pour les deux.
        assertEquals(listOf(false, false, true), rows.map { it.needsVisit(open = true, send = true) })
    }
}
