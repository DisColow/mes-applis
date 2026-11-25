package com.discolow.cadeauxgo.bot

import kotlin.math.abs

/** Une ligne de la liste d'amis, repérée par son cœur (coordonnées de travail). */
data class FriendRow(
    /** Position du repère (coin haut-gauche du cœur le plus à gauche). */
    val x: Int,
    val y: Int,
    val hasGift: Boolean,
    val alreadySent: Boolean,
) {
    fun needsVisit(open: Boolean, send: Boolean) = (open && hasGift) || (send && !alreadySent)
}

/**
 * Analyse des lignes de la liste d'amis (code pur, testé dans FriendRowsTest).
 * Les positions sont des coins haut-gauche ; on compare les centres verticaux.
 */
object FriendRows {

    class Box(val x: Int, val y: Int, val w: Int, val h: Int) {
        val cy get() = y + h / 2
    }

    /**
     * [hearts] : toutes les occurrences du repère de ligne (plusieurs cœurs par ligne).
     * [gifts], [sentArrows] : icônes trouvées dans la liste.
     * Les lignes trop près du haut (sous l'en-tête) ou du bas (sous les boutons flottants)
     * sont écartées : leurs icônes peuvent être cachées.
     */
    fun analyze(
        hearts: List<Box>,
        gifts: List<Box>,
        sentArrows: List<Box>,
        frameH: Int,
        topMargin: Float = 0.15f,
        bottomMargin: Float = 0.88f,
    ): List<FriendRow> {
        // 1) Un repère par ligne : les cœurs d'une même ligne sont à la même hauteur.
        val sorted = hearts.sortedWith(compareBy({ it.cy }, { it.x }))
        val anchors = ArrayList<Box>()
        for (h in sorted) {
            val same = anchors.indexOfFirst { abs(it.cy - h.cy) <= maxOf(8, h.h / 2) }
            if (same < 0) anchors += h else if (h.x < anchors[same].x) anchors[same] = h
        }
        anchors.sortBy { it.cy }
        if (anchors.isEmpty()) return emptyList()

        // 2) Hauteur d'une ligne : écart médian entre repères (sinon ~15 % de l'écran).
        val gaps = anchors.zipWithNext { a, b -> b.cy - a.cy }.filter { it > 0 }.sorted()
        val spacing = if (gaps.isEmpty()) (frameH * 0.15f).toInt() else gaps[gaps.size / 2]

        // 3) Chaque icône est rattachée à la ligne dont le repère est le plus proche verticalement.
        fun owner(b: Box): Int {
            var best = -1
            var bd = Int.MAX_VALUE
            anchors.forEachIndexed { i, a ->
                val d = abs(a.cy - b.cy)
                if (d < bd) { bd = d; best = i }
            }
            return if (bd <= spacing * 0.6f) best else -1
        }
        val giftRows = gifts.map(::owner).toSet()
        val sentRows = sentArrows.map(::owner).toSet()

        val top = frameH * topMargin
        val bottom = frameH * bottomMargin
        return anchors.mapIndexedNotNull { i, a ->
            if (a.cy < top || a.cy > bottom) null
            else FriendRow(a.x, a.y, hasGift = i in giftRows, alreadySent = i in sentRows)
        }
    }
}
