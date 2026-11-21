package fr.mesapplis.voxtune.dsp

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Détecteur de hauteur YIN (de Cheveigné & Kawahara, 2002) pour voix monophonique.
 * Renvoie la fréquence fondamentale en Hz, ou -1 si le signal n'est pas voisé.
 */
class PitchDetector(
    private val sampleRate: Float,
    private val size: Int,
    minFreq: Float = 70f,
    maxFreq: Float = 1100f,
    private val threshold: Float = 0.15f,
    var silenceRms: Float = 0.008f,
) {
    private val tauMax = min((sampleRate / minFreq).toInt(), size / 2)
    private val tauMin = max(2, (sampleRate / maxFreq).toInt())
    private val diff = FloatArray(tauMax + 2)

    /** Qualité de la dernière détection (0 = parfaite, 1 = bruit). */
    var lastAperiodicity = 1f
        private set

    fun detect(x: FloatArray): Float {
        var energy = 0f
        for (i in 0 until size) energy += x[i] * x[i]
        if (sqrt(energy / size) < silenceRms) {
            lastAperiodicity = 1f
            return -1f
        }

        val w = size - tauMax - 1
        for (tau in 1..tauMax + 1) {
            var s = 0f
            for (i in 0 until w) {
                val d = x[i] - x[i + tau]
                s += d * d
            }
            diff[tau] = s
        }

        // Différence moyenne normalisée cumulée
        diff[0] = 1f
        var running = 0f
        for (tau in 1..tauMax + 1) {
            running += diff[tau]
            diff[tau] = if (running > 0f) diff[tau] * tau / running else 1f
        }

        var tau = tauMin
        var found = -1
        while (tau <= tauMax) {
            if (diff[tau] < threshold) {
                while (tau + 1 <= tauMax && diff[tau + 1] < diff[tau]) tau++
                found = tau
                break
            }
            tau++
        }
        if (found < 0) {
            lastAperiodicity = 1f
            return -1f
        }
        lastAperiodicity = diff[found]

        // Interpolation parabolique pour une précision sub-échantillon
        val s0 = diff[found - 1]
        val s1 = diff[found]
        val s2 = diff[found + 1]
        val denom = s0 + s2 - 2f * s1
        val shift = if (denom != 0f) 0.5f * (s0 - s2) / denom else 0f
        val betterTau = found + shift.coerceIn(-1f, 1f)
        return sampleRate / betterTau
    }
}
