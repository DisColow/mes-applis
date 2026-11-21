package fr.mesapplis.voxtune.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Transposeur temps réel à ligne à retard avec deux têtes de lecture (fenêtres de Hann).
 *
 * Pour éviter l'effet de filtre en peigne, l'écart entre les deux têtes est maintenu
 * à un nombre entier de périodes de la voix : les deux lectures sont en phase pendant
 * les fondus. Latence ≈ fenêtre/2 (~13 ms).
 */
class PitchShifter(sampleRate: Float, windowMs: Float = 26f) {
    private val size = 1 shl 13
    private val mask = size - 1
    private val buf = FloatArray(size)
    private var writePos = 0
    private val baseWindow = sampleRate * windowMs / 1000f
    private var preferredWindow = baseWindow

    private val delay = DoubleArray(2)
    private val window = DoubleArray(2)

    private val tableSize = 1024
    private val hann = FloatArray(tableSize + 1) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / tableSize)).toFloat()
    }

    init { reset() }

    fun reset() {
        buf.fill(0f)
        window[0] = baseWindow.toDouble(); window[1] = baseWindow.toDouble()
        delay[0] = 0.0; delay[1] = baseWindow / 2.0
        preferredWindow = baseWindow
    }

    /** Indique la période actuelle de la voix (en échantillons), ou ≤0 si non voisé. */
    fun setPeriod(period: Float) {
        preferredWindow = if (period <= 0f) baseWindow else {
            val k = (baseWindow / (2f * period)).roundToInt().coerceAtLeast(1)
            (2f * k * period).coerceAtMost(size / 3f)
        }
    }

    /** Traite un échantillon. [ratio] = facteur de fréquence (2 = une octave au-dessus). */
    fun process(x: Float, ratio: Float): Float {
        buf[writePos] = x
        val step = 1.0 - ratio
        var out = 0f
        var gsum = 0f
        for (i in 0..1) {
            var d = delay[i] + step
            if (d < 0.0 || d >= window[i]) {
                // Tête silencieuse (gain nul) : on la replace à W/2 de l'autre tête.
                val w = preferredWindow.toDouble()
                val other = delay[1 - i]
                window[i] = w
                d = if (step < 0) other + w / 2 else other - w / 2
                d = d.coerceIn(0.0, w - 1e-6)
            }
            delay[i] = d
            val g = hann[(d / window[i] * tableSize).toInt().coerceIn(0, tableSize)]
            out += tap(d) * g
            gsum += g
        }
        writePos = (writePos + 1) and mask
        return if (gsum > 0.3f) out / gsum else out / 0.3f
    }

    private fun tap(delay: Double): Float {
        val pos = writePos - 1.0 - delay
        val fl = floor(pos)
        val frac = (pos - fl).toFloat()
        val i0 = fl.toInt() and mask
        val i1 = (i0 + 1) and mask
        return buf[i0] + (buf[i1] - buf[i0]) * frac
    }
}
