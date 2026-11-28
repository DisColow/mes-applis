package com.discolow.cadeauxgo.vision

import com.discolow.cadeauxgo.bot.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Mesure du défilement (Matcher.verticalShift) sur de vraies captures. */
class ScrollTest {

    /** Capture dont le contenu sous [headerFrac] remonte de [shift] px (en-tête fixe, comme dans le jeu). */
    private fun scrolled(name: String, shift: Int, headerFrac: Float): Frame {
        val f = RealScreens.frame(name)
        val sw = f.srcW
        val sh = f.srcH
        val ws = (shift * f.factor).toInt()
        val head = (headerFrac * f.h).toInt()
        val px = f.argb.copyOf()
        for (y in head until f.h) for (x in 0 until f.w) {
            val src = y + ws
            px[y * f.w + x] = if (src < f.h) f.argb[src * f.w + x] else f.argb[(f.h - 1) * f.w + x]
        }
        return Frame(f.w, f.h, px, sw, sh)
    }

    @Test
    fun decalagesConnus() {
        for ((name, head) in listOf("o_010" to 0.10f, "t_030" to 0.22f, "shot_list" to 0.11f)) {
            val before = RealScreens.frame(name)
            for (s in listOf(60, 250, 450, 560)) {
                val real = (s * before.srcH / 1280f).toInt()
                val after = scrolled(name, real, head)
                val m = Matcher.verticalShift(before, after)
                val expected = (real * before.factor).toInt()
                assertNotNull("$name décalage $real non mesuré", m)
                assertTrue("$name : mesuré $m, attendu $expected", abs(m!! - expected) <= 3)
            }
        }
    }

    @Test
    fun ecranImmobile() {
        val f = RealScreens.frame("t_030")
        assertEquals(0, Matcher.verticalShift(f, RealScreens.frame("t_030")))
    }

    @Test
    fun vraiDefilementDeLaVideo() {
        // Deux images successives de la vidéo de transfert pendant un défilement : une fois le décalage
        // mesuré appliqué, les « PC » de la grille doivent se superposer.
        val pc = RealScreens.builtins.first { it.def.role == Role.TR_CELL }
        var checked = 0
        for ((a, b) in listOf("t_023" to "t_024", "t_024" to "t_025", "t_025" to "t_026", "t_033" to "t_034", "t_034" to "t_035", "t_035" to "t_036")) {
            val fa = RealScreens.frame(a)
            val fb = RealScreens.frame(b)
            val s = Matcher.verticalShift(fa, fb) ?: continue
            if (s < 8) continue
            val la = Matcher.findAll(fa, pc.pattern, 0.82, pc.def.colorTol)
            val lb = Matcher.findAll(fb, pc.pattern, 0.82, pc.def.colorTol)
            // Chaque « PC » de b (assez bas pour exister dans a) correspond à un « PC » de a décalé de s.
            val comparable = lb.filter { it.y + s < fa.h * 0.8 }
            assertTrue("$a→$b : trop peu de cases", comparable.size >= 3)
            for (m in comparable) {
                assertTrue("$a→$b (décalage $s) : case ($m) sans correspondance",
                    la.any { abs(it.x - m.x) <= 6 && abs(it.y - (m.y + s)) <= 6 })
            }
            checked++
        }
        assertTrue("au moins deux paires en défilement : $checked", checked >= 2)
    }
}
