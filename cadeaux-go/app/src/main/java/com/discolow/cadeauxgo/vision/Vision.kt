package com.discolow.cadeauxgo.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Reconnaissance d'image sans dépendance : corrélation croisée normalisée (NCC) par
 * recherche grossière (image réduite) puis affinage à pleine résolution de travail,
 * avec vérification de la couleur moyenne (un bouton grisé ≠ un bouton actif).
 * Code Kotlin pur (aucune API Android) pour pouvoir être testé sur PC.
 */

/** Image en niveaux de gris + images intégrales (somme et somme des carrés). */
class GrayImage(val w: Int, val h: Int, val px: FloatArray) {
    private val stride = w + 1
    val sum = DoubleArray((w + 1) * (h + 1))
    val sq = DoubleArray((w + 1) * (h + 1))

    init {
        for (y in 0 until h) {
            var rs = 0.0
            var rq = 0.0
            val row = y * w
            val o = (y + 1) * stride
            val p = y * stride
            for (x in 0 until w) {
                val v = px[row + x].toDouble()
                rs += v
                rq += v * v
                sum[o + x + 1] = sum[p + x + 1] + rs
                sq[o + x + 1] = sq[p + x + 1] + rq
            }
        }
    }

    fun rectSum(a: DoubleArray, x: Int, y: Int, rw: Int, rh: Int): Double {
        val x2 = x + rw
        val y2 = y + rh
        return a[y2 * stride + x2] - a[y * stride + x2] - a[y2 * stride + x] + a[y * stride + x]
    }

    /** Réduction par moyenne de blocs k×k. */
    fun downscale(k: Int): GrayImage {
        if (k <= 1) return this
        val nw = max(1, w / k)
        val nh = max(1, h / k)
        val out = FloatArray(nw * nh)
        val inv = 1f / (k * k)
        for (y in 0 until nh) for (x in 0 until nw) {
            var s = 0f
            for (j in 0 until k) {
                val r = (y * k + j) * w + x * k
                for (i in 0 until k) s += px[r + i]
            }
            out[y * nw + x] = s * inv
        }
        return GrayImage(nw, nh, out)
    }

    companion object {
        fun fromArgb(w: Int, h: Int, argb: IntArray): GrayImage {
            val g = FloatArray(w * h)
            for (i in g.indices) {
                val c = argb[i]
                g[i] = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
            }
            return GrayImage(w, h, g)
        }
    }
}

private fun meanColor(w: Int, argb: IntArray, x: Int, y: Int, rw: Int, rh: Int): IntArray {
    var r = 0L
    var g = 0L
    var b = 0L
    for (j in y until y + rh) {
        val row = j * w
        for (i in x until x + rw) {
            val c = argb[row + i]
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
        }
    }
    val n = max(1, rw * rh)
    return intArrayOf((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}

/**
 * Correction d'une teinte appliquée à tout l'écran (filtre de lumière bleue, mode nuit, confort des
 * yeux…) : ces filtres dessinent un calque coloré semi-transparent qui apparaît dans les captures et
 * fausse les couleurs (blanc → rose, turquoise → gris…). La forme des boutons reste reconnue (la
 * corrélation normalisée y est insensible), mais pas leur couleur.
 *
 * Modèle : couleur mesurée = a × couleur réelle + calque, avec un calque chaud dont la composante la
 * plus faible est quasi nulle. Le blanc de l'interface (pixels les plus clairs) donne alors a = (canal
 * le plus faible du blanc) / 255 et le calque = blanc − 255·a. Mesuré sur une vraie vidéo : blanc
 * (227, 184, 176) → a = 0,69, calque (51, 8, 0) ; turquoise (80, 109, 106) corrigé en (42, 146, 153)
 * pour (42, 146, 152) réel.
 */
class Tint private constructor(private val alpha: Float, private val layer: IntArray) {

    override fun toString() = "Tint(a=${"%.2f".format(alpha)}, calque=${layer.toList()})"

    fun correct(rgb: IntArray): IntArray = IntArray(3) { c -> ((rgb[c] - layer[c]) / alpha).toInt().coerceIn(0, 255) }

    companion object {
        /** Écart minimal entre canaux du « blanc » pour considérer l'écran comme teinté. */
        private const val MIN_CAST = 25

        /** Au-delà, le « blanc » n'est pas assombri par un calque : pas de filtre. */
        private const val MAX_FILTERED_WHITE = 242

        /** Variance maximale (par canal) des pixels les plus clairs : écart-type ≈ 12. */
        private const val UNIFORM_VAR = 150.0

        /** null si l'écran n'est pas teinté (blanc neutre ou non assombri). */
        fun estimate(w: Int, h: Int, argb: IntArray): Tint? {
            val n = w * h
            if (n < 100) return null
            // Histogramme des luminances (un pixel sur 4 suffit) → 2 % les plus clairs.
            val hist = IntArray(256)
            val step = 4
            var count = 0
            for (i in 0 until n step step) {
                hist[lum(argb[i])]++; count++
            }
            val k = max(1, count / 50)
            var hi = 255
            var acc = 0
            while (hi > 0 && acc + hist[hi] < k) { acc += hist[hi]; hi-- }
            val sum = LongArray(3)
            val sq = LongArray(3)
            var m = 0
            for (i in 0 until n step step) {
                val c = argb[i]
                if (lum(c) >= hi) {
                    for (ch in 0..2) {
                        val v = (c shr (16 - 8 * ch)) and 0xFF
                        sum[ch] += v; sq[ch] += v.toLong() * v
                    }
                    m++
                }
            }
            if (m == 0) return null
            val white = IntArray(3) { (sum[it] / m).toInt() }
            // Le blanc de l'interface est une couleur uniforme ; des zones claires disparates ne
            // permettent pas d'estimer un calque.
            for (ch in 0..2) {
                val mean = sum[ch].toDouble() / m
                if (sq[ch].toDouble() / m - mean * mean > UNIFORM_VAR) return null
            }
            // Un vrai filtre assombrit le blanc : son canal le plus fort n'atteint plus 255. Un écran dont les
            // zones claires sont simplement colorées (dégradé bleu-vert…) garde un canal proche de 255.
            if (white.max() >= MAX_FILTERED_WHITE) return null
            if (white.max() - white.min() < MIN_CAST) return null
            val alpha = white.min() / 255f
            if (alpha < 0.3f) return null
            return Tint(alpha, IntArray(3) { white[it] - white.min() })
        }

        private fun lum(c: Int) =
            (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)).toInt().coerceIn(0, 255)
    }
}

/**
 * Une capture d'écran réduite à la résolution de travail.
 * [srcW]/[srcH] = taille réelle de l'écran (pour convertir en coordonnées de toucher).
 */
class Frame(val w: Int, val h: Int, val argb: IntArray, val srcW: Int = w, val srcH: Int = h) {
    val gray = GrayImage.fromArgb(w, h, argb)
    private val levels = HashMap<Int, GrayImage>()

    /** Facteur résolution de travail / résolution réelle. */
    val factor: Float get() = w.toFloat() / srcW

    fun level(k: Int): GrayImage =
        if (k <= 1) gray else synchronized(levels) { levels.getOrPut(k) { gray.downscale(k) } }

    /** Teinte de l'écran à corriger (filtre de lumière bleue…), ou null si les couleurs sont normales. */
    val tint: Tint? by lazy { Tint.estimate(w, h, argb) }

    /** Couleur moyenne d'une zone, corrigée de la teinte de l'écran s'il y en a une. */
    fun meanColor(x: Int, y: Int, rw: Int, rh: Int): IntArray {
        val raw = meanColor(w, argb, x, y, rw, rh)
        return tint?.correct(raw) ?: raw
    }
}

/** Un motif appris (bouton, icône…) à la résolution de travail. */
class Pattern(val w: Int, val h: Int, val argb: IntArray) {
    private val gray = GrayImage.fromArgb(w, h, argb)
    val meanRgb: IntArray = meanColor(w, argb, 0, 0, w, h)

    /** Écart-type des niveaux de gris : un motif trop uniforme n'est pas reconnaissable. */
    val std: Double

    init {
        val n = w * h
        val s = gray.rectSum(gray.sum, 0, 0, w, h)
        val s2 = gray.rectSum(gray.sq, 0, 0, w, h)
        std = sqrt(max(0.0, s2 / n - (s / n) * (s / n)))
    }

    /** Motif centré (moyenne retirée) à une échelle donnée. */
    class Level(val w: Int, val h: Int, val tz: FloatArray, val norm: Double)

    private val levels = HashMap<Int, Level>()

    fun level(k: Int): Level = synchronized(levels) {
        levels.getOrPut(k) {
            val g = gray.downscale(k)
            var mean = 0.0
            for (v in g.px) mean += v
            mean /= g.px.size
            val tz = FloatArray(g.px.size)
            var n2 = 0.0
            for (i in tz.indices) {
                val v = (g.px[i] - mean).toFloat()
                tz[i] = v
                n2 += v.toDouble() * v
            }
            Level(g.w, g.h, tz, sqrt(n2))
        }
    }
}

/** Position (coin haut-gauche, résolution de travail) et score de ressemblance (0..1). */
data class Match(val x: Int, val y: Int, val score: Double)

object Matcher {

    fun ncc(img: GrayImage, x: Int, y: Int, lv: Pattern.Level): Double {
        val n = lv.w * lv.h
        val s = img.rectSum(img.sum, x, y, lv.w, lv.h)
        val s2 = img.rectSum(img.sq, x, y, lv.w, lv.h)
        val varI = s2 - s * s / n
        if (varI < 1e-3 || lv.norm < 1e-6) return 0.0
        val tz = lv.tz
        val px = img.px
        val iw = img.w
        var cross = 0.0
        for (j in 0 until lv.h) {
            var r = 0f
            val ib = (y + j) * iw + x
            val tb = j * lv.w
            for (i in 0 until lv.w) r += tz[tb + i] * px[ib + i]
            cross += r
        }
        return cross / (lv.norm * sqrt(varI))
    }

    /**
     * Cherche [p] dans [frame]. Renvoie la meilleure position si le score atteint [threshold]
     * et si la couleur moyenne est proche (écart max par canal ≤ [colorTol]).
     */
    fun find(frame: Frame, p: Pattern, threshold: Double, colorTol: Int = 40): Match? =
        findAll(frame, p, threshold, colorTol, maxResults = 3).maxByOrNull { it.score }

    /**
     * Toutes les occurrences de [p] (ex. l'icône cadeau sur chaque ligne de la liste d'amis),
     * sans doublons, triées de haut en bas.
     */
    fun findAll(frame: Frame, p: Pattern, threshold: Double, colorTol: Int = 40, maxResults: Int = 60): List<Match> {
        if (p.w > frame.w || p.h > frame.h || p.w < 2 || p.h < 2) return emptyList()
        val k = intArrayOf(4, 2).firstOrNull { p.w / it >= 6 && p.h / it >= 6 } ?: 1
        val fine = p.level(1)
        val maxX = frame.w - fine.w
        val maxY = frame.h - fine.h

        // 1) Pics de ressemblance sur l'image réduite.
        val img = frame.level(k)
        val lv = p.level(k)
        val mw = img.w - lv.w + 1
        val mh = img.h - lv.h + 1
        if (mw <= 0 || mh <= 0) return emptyList()
        val map = FloatArray(mw * mh)
        for (y in 0 until mh) for (x in 0 until mw) map[y * mw + x] = ncc(img, x, y, lv).toFloat()
        val cands = ArrayList<IntArray>()
        for (n in 0 until maxResults) {
            var bi = -1
            var bv = 0.35f
            for (i in map.indices) if (map[i] > bv) { bv = map[i]; bi = i }
            if (bi < 0) break
            val cx = bi % mw
            val cy = bi / mw
            cands += intArrayOf(cx * k, cy * k)
            // Suppression des non-maxima autour du pic.
            for (y in max(0, cy - lv.h / 2) until min(mh, cy + lv.h / 2 + 1))
                for (x in max(0, cx - lv.w / 2) until min(mw, cx + lv.w / 2 + 1)) map[y * mw + x] = -1f
        }
        val radius = k + 1

        // 2) Affinage + vérification couleur + suppression des doublons.
        val out = ArrayList<Match>()
        for (c in cands) {
            val m = bestIn(
                frame.gray, fine,
                max(0, c[0] - radius), max(0, c[1] - radius),
                min(maxX, c[0] + radius), min(maxY, c[1] + radius),
            ) ?: continue
            if (m.score < threshold) continue
            val col = frame.meanColor(m.x, m.y, fine.w, fine.h)
            val diff = (0..2).maxOf { abs(col[it] - p.meanRgb[it]) }
            if (diff > colorTol) continue
            val dup = out.indexOfFirst { abs(it.x - m.x) < fine.w / 2 && abs(it.y - m.y) < fine.h / 2 }
            if (dup < 0) out += m else if (m.score > out[dup].score) out[dup] = m
        }
        return out.sortedWith(compareBy({ it.y }, { it.x }))
    }

    /**
     * La première occurrence dans l'ordre de lecture (ligne par ligne, de gauche à droite) :
     * deux occurrences dont l'écart vertical est inférieur à [rowTol] sont sur la même ligne.
     */
    fun firstInReadingOrder(matches: List<Match>, rowTol: Int): Match? {
        val top = matches.minOfOrNull { it.y } ?: return null
        return matches.filter { it.y - top < rowTol }.minByOrNull { it.x }
    }

    private fun bestIn(img: GrayImage, lv: Pattern.Level, x0: Int, y0: Int, x1: Int, y1: Int): Match? {
        var best: Match? = null
        for (y in y0..y1) for (x in x0..x1) {
            val s = ncc(img, x, y, lv)
            if (best == null || s > best.score) best = Match(x, y, s)
        }
        return best
    }

    /** Proportion de pixels (image réduite) qui ont nettement changé entre deux captures. */
    fun changedFraction(a: Frame, b: Frame): Double {
        val ga = a.level(4)
        val gb = b.level(4)
        if (ga.w != gb.w || ga.h != gb.h) return 1.0
        var n = 0
        for (i in ga.px.indices) if (abs(ga.px[i] - gb.px[i]) > 20f) n++
        return n.toDouble() / ga.px.size
    }
}

/** Réduction d'image par moyenne de surface (identique sur le téléphone et dans les tests). */
object Resize {
    fun area(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        if (sw == dw && sh == dh) return src.copyOf()
        val out = IntArray(dw * dh)
        val fx = sw.toDouble() / dw
        val fy = sh.toDouble() / dh
        val r = DoubleArray(dw)
        val g = DoubleArray(dw)
        val b = DoubleArray(dw)
        val wsum = DoubleArray(dw)
        for (dy in 0 until dh) {
            java.util.Arrays.fill(r, 0.0); java.util.Arrays.fill(g, 0.0)
            java.util.Arrays.fill(b, 0.0); java.util.Arrays.fill(wsum, 0.0)
            val y0 = dy * fy
            val y1 = min(sh.toDouble(), (dy + 1) * fy)
            var sy = y0.toInt()
            while (sy < y1 && sy < sh) {
                val wy = min(y1, sy + 1.0) - max(y0, sy.toDouble())
                if (wy > 0) {
                    val row = sy * sw
                    for (dx in 0 until dw) {
                        val x0 = dx * fx
                        val x1 = min(sw.toDouble(), (dx + 1) * fx)
                        var sx = x0.toInt()
                        while (sx < x1 && sx < sw) {
                            val w = (min(x1, sx + 1.0) - max(x0, sx.toDouble())) * wy
                            if (w > 0) {
                                val c = src[row + sx]
                                r[dx] += w * ((c shr 16) and 0xFF)
                                g[dx] += w * ((c shr 8) and 0xFF)
                                b[dx] += w * (c and 0xFF)
                                wsum[dx] += w
                            }
                            sx++
                        }
                    }
                }
                sy++
            }
            val o = dy * dw
            for (dx in 0 until dw) {
                val s = if (wsum[dx] > 0) wsum[dx] else 1.0
                out[o + dx] = (0xFF shl 24) or
                    ((r[dx] / s + 0.5).toInt().coerceIn(0, 255) shl 16) or
                    ((g[dx] / s + 0.5).toInt().coerceIn(0, 255) shl 8) or
                    (b[dx] / s + 0.5).toInt().coerceIn(0, 255)
            }
        }
        return out
    }

    /** Capture pleine résolution → image de travail de largeur [workW]. */
    fun frame(full: IntArray, w: Int, h: Int, workW: Int): Frame {
        val dw = min(workW, w)
        val dh = max(1, (h.toLong() * dw / w).toInt())
        return Frame(dw, dh, area(full, w, h, dw, dh), w, h)
    }

    /** Motif appris sur un écran de largeur [tplScreenW] → échelle de travail de largeur [workW]. */
    fun pattern(px: IntArray, w: Int, h: Int, tplScreenW: Int, workW: Int): Pattern {
        val f = workW.toDouble() / tplScreenW
        val dw = max(2, (w * f + 0.5).toInt())
        val dh = max(2, (h * f + 0.5).toInt())
        return Pattern(dw, dh, area(px, w, h, dw, dh))
    }
}
