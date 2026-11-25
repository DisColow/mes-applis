package com.discolow.cadeauxgo.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MatcherTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Fond « jeu » : grands aplats colorés + un peu de bruit. */
    private fun background(w: Int, h: Int, seed: Int): IntArray {
        val rnd = Random(seed)
        val blocks = Array(64) { rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) }
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val c = blocks[((x / 40) * 7 + (y / 55) * 3) % blocks.size]
            val n = rnd.nextInt(-10, 11)
            rgb(
                (((c shr 16) and 0xFF) + n).coerceIn(0, 255),
                (((c shr 8) and 0xFF) + n).coerceIn(0, 255),
                ((c and 0xFF) + n).coerceIn(0, 255),
            )
        }
    }

    /** Un « bouton » vert avec un texte blanc stylisé. */
    private fun button(w: Int, h: Int, green: Boolean): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        val text = y in h / 3 until 2 * h / 3 && (x / 5) % 3 != 0 && x in 8 until w - 8
        when {
            text -> rgb(255, 255, 255)
            green -> rgb(60, 200, 120)
            else -> rgb(150, 150, 150)
        }
    }

    private fun paste(dst: IntArray, dw: Int, src: IntArray, sw: Int, sh: Int, x0: Int, y0: Int) {
        for (y in 0 until sh) for (x in 0 until sw) dst[(y0 + y) * dw + x0 + x] = src[y * sw + x]
    }

    @Test
    fun trouveLeBoutonALaBonnePlace() {
        val w = 480
        val h = 1000
        val img = background(w, h, 1)
        val bw = 150
        val bh = 44
        val btn = button(bw, bh, green = true)
        paste(img, w, btn, bw, bh, 163, 731)
        val m = Matcher.find(Frame(w, h, img), Pattern(bw, bh, btn), 0.85)
        assertNotNull(m)
        assertEquals(163, m!!.x)
        assertEquals(731, m.y)
        assertTrue(m.score > 0.95)
    }

    @Test
    fun petitMotifRechercheDirecte() {
        val w = 300
        val h = 500
        val img = background(w, h, 2)
        val bw = 10
        val bh = 10
        val icon = IntArray(bw * bh) { i -> if ((i % bw + i / bw) % 4 < 2) rgb(250, 30, 30) else rgb(20, 20, 20) }
        paste(img, w, icon, bw, bh, 57, 401)
        val m = Matcher.find(Frame(w, h, img), Pattern(bw, bh, icon), 0.85)
        assertNotNull(m)
        assertEquals(57, m!!.x)
        assertEquals(401, m.y)
    }

    @Test
    fun absentNeDonneRien() {
        val w = 480
        val h = 1000
        val img = background(w, h, 3)
        val btn = button(150, 44, green = true)
        assertNull(Matcher.find(Frame(w, h, img), Pattern(150, 44, btn), 0.85))
    }

    @Test
    fun boutonGriseRefuse() {
        val w = 480
        val h = 1000
        val img = background(w, h, 4)
        paste(img, w, button(150, 44, green = false), 150, 44, 100, 300)
        // Même forme, mais grise : la vérification de couleur doit le rejeter.
        assertNull(Matcher.find(Frame(w, h, img), Pattern(150, 44, button(150, 44, green = true)), 0.85))
    }

    @Test
    fun detectionDeChangement() {
        val w = 480
        val h = 1000
        val a = background(w, h, 5)
        val b = a.copyOf()
        assertTrue(Matcher.changedFraction(Frame(w, h, a), Frame(w, h, b)) < 0.001)
        paste(b, w, IntArray(200 * 300) { rgb(255, 255, 255) }, 200, 300, 50, 50)
        assertTrue(Matcher.changedFraction(Frame(w, h, a), Frame(w, h, b)) > 0.05)
    }

    @Test
    fun trouveToutesLesIcones() {
        val w = 480
        val h = 1000
        val img = background(w, h, 6)
        val iw = 24
        val ih = 24
        val icon = IntArray(iw * ih) { i ->
            val x = i % iw
            val y = i / iw
            if (x in 4 until 20 && y in 8 until 22) {
                if (x in 11..12 || y in 13..14) rgb(255, 230, 0) else rgb(230, 40, 160)
            } else {
                rgb(255, 255, 255)
            }
        }
        val ys = listOf(120, 330, 540, 750)
        for (y in ys) paste(img, w, icon, iw, ih, 400, y)
        val all = Matcher.findAll(Frame(w, h, img), Pattern(iw, ih, icon), 0.85)
        assertEquals(ys, all.map { it.y })
        assertTrue(all.all { it.x == 400 })
    }

    @Test
    fun premierCadeauDeLaListe() {
        // Grille 2×3 de nœuds roses, légèrement décalés en hauteur sur une même ligne.
        val bows = listOf(
            Match(310, 92, 0.97), Match(60, 95, 0.90),
            Match(62, 240, 0.99), Match(308, 238, 0.95),
            Match(61, 385, 0.98), Match(309, 386, 0.96),
        )
        assertEquals(Match(60, 95, 0.90), Matcher.firstInReadingOrder(bows, rowTol = 30))
        assertNull(Matcher.firstInReadingOrder(emptyList(), 30))
    }
}
