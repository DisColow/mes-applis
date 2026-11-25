package com.discolow.cadeauxgo.bot

import com.discolow.cadeauxgo.vision.Frame
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Une case de la grille de Pokémon (coordonnées de travail). */
data class StorageCell(
    /** Coin haut-gauche du « PC ». */
    val x: Int,
    val y: Int,
    /** Point à toucher (le Pokémon). */
    val tapX: Int,
    val tapY: Int,
    val selected: Boolean,
)

/**
 * Analyse de la grille du stockage de Pokémon (code pur, testé sur de vraies captures).
 * Distances mesurées sur une vidéo de 590 px de large, mises à l'échelle de l'écran.
 */
object StorageGrid {
    private const val REF = 590f

    /**
     * [labels] : coins haut-gauche des « PC » trouvés. [listTop] : bas de la ligne
     * « AFFICHER LES ÉVOLUTIONS » (les cases au-dessus sont ignorées). Les cases trop basses
     * (sous les boutons du mode sélection) sont ignorées aussi.
     */
    fun cells(f: Frame, labels: List<Pair<Int, Int>>, listTop: Int): List<StorageCell> {
        val u = f.w / REF
        val bottom = f.h * 0.82f
        // Lignes de la grille : même hauteur à quelques pixels près.
        val sorted = labels.sortedBy { it.second }
        val rows = ArrayList<MutableList<Pair<Int, Int>>>()
        for (l in sorted) {
            val row = rows.lastOrNull()
            if (row != null && abs(row[0].second - l.second) < 30 * u) row += l else rows += mutableListOf(l)
        }
        return rows.flatMap { r -> r.sortedBy { it.first } }
            .filter { (_, y) -> y > listTop && y + 150 * u < bottom }
            .map { (x, y) ->
                // Fond de la case (à gauche, sous le Pokémon) comparé au fond entre deux colonnes :
                // une case sélectionnée a un fond gris-vert nettement plus sombre en rouge.
                val sy = (y + 105 * u).toInt()
                val sh = max(2, (15 * u).toInt())
                val cx = max(0, (x - 32 * u).toInt())
                val cw = max(2, (15 * u).toInt())
                val gw = max(2, (5 * u).toInt())
                val yy = min(sy, f.h - sh)
                val cell = f.meanColor(cx, yy, cw, sh)[0]
                // Deux interstices (colonnes 1-2 et 2-3) : le plus clair sert de référence
                // (l'animation d'un appui peut assombrir l'un des deux un instant).
                val gap = max(
                    f.meanColor((198 * u).toInt(), yy, gw, sh)[0],
                    f.meanColor((386 * u).toInt(), yy, gw, sh)[0],
                )
                StorageCell(x, y, (x + 46 * u).toInt(), (y + 67 * u).toInt(), selected = cell < gap - 12)
            }
    }
}
