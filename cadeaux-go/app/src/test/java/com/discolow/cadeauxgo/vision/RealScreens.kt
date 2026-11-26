package com.discolow.cadeauxgo.vision

import com.discolow.cadeauxgo.bot.Builtin
import com.discolow.cadeauxgo.bot.BuiltinDef
import java.io.File

/** Charge les vraies captures du jeu et les motifs intégrés, préparés comme sur le téléphone. */
object RealScreens {
    const val WORK = 480

    /** javax.imageio n'est pas visible à la compilation des tests Android, mais existe à l'exécution. */
    private fun pixels(f: File): Triple<IntArray, Int, Int> {
        val img = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, f)
            ?: error("image illisible : $f")
        val c = img.javaClass
        val w = c.getMethod("getWidth").invoke(img) as Int
        val h = c.getMethod("getHeight").invoke(img) as Int
        val get = c.getMethod(
            "getRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            IntArray::class.java, Int::class.java, Int::class.java,
        )
        val px = get.invoke(img, 0, 0, w, h, null, 0, w) as IntArray
        return Triple(px, w, h)
    }

    /**
     * Les vraies captures montrent des pseudos (le joueur, ses amis) : elles ne sont pas publiées.
     * Sans elles, ces tests sont ignorés au lieu d'échouer.
     */
    private fun capture(name: String): File {
        val f = File("src/test/resources/real/$name.jpg")
        org.junit.Assume.assumeTrue("capture privée absente : $name", f.exists())
        return f
    }

    fun frame(name: String): Frame {
        val (px, w, h) = pixels(capture(name))
        return Resize.frame(px, w, h, WORK)
    }

    /**
     * Même capture vue à travers un filtre d'écran rouge semi-transparent (mesuré sur une vraie vidéo :
     * couleur' = 0,69 × couleur + (51, 9, 3)).
     */
    fun frameTinted(name: String): Frame {
        val (px, w, h) = pixels(capture(name))
        for (i in px.indices) {
            val c = px[i]
            val r = (0.69f * ((c shr 16) and 0xFF) + 51).toInt().coerceIn(0, 255)
            val g = (0.69f * ((c shr 8) and 0xFF) + 9).toInt().coerceIn(0, 255)
            val b = (0.69f * (c and 0xFF) + 3).toInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Resize.frame(px, w, h, WORK)
    }

    /**
     * Même capture avec un rectangle sombre à l'endroit du panneau de Cadeaux GO (fractions de
     * l'écran : x0, y0, x1, y1), comme sur une vraie capture prise pendant la routine.
     */
    fun frameWithPanel(name: String, x0: Float, y0: Float, x1: Float, y1: Float): Frame {
        val (px, w, h) = pixels(capture(name))
        for (y in (y0 * h).toInt() until (y1 * h).toInt()) for (x in (x0 * w).toInt() until (x1 * w).toInt()) {
            px[y * w + x] = 0xEE1E1B24.toInt() or (0xFF shl 24)
        }
        return Resize.frame(px, w, h, WORK)
    }

    class Loaded(val def: BuiltinDef, val pattern: Pattern)

    val builtins: List<Loaded> by lazy {
        Builtin.all.map { d ->
            val (px, w, h) = pixels(File("src/main/assets/builtin/${d.file}"))
            Loaded(d, Resize.pattern(px, w, h, d.screenW, WORK))
        }
    }
}
