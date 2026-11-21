package fr.mesapplis.voxtune.dsp

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

object Music {
    val NOTE_NAMES = arrayOf("Do", "Do#", "Ré", "Mi♭", "Mi", "Fa", "Fa#", "Sol", "La♭", "La", "Si♭", "Si")

    fun hzToMidi(hz: Float): Float = (69.0 + 12.0 * ln(hz / 440.0) / ln(2.0)).toFloat()
    fun midiToHz(midi: Float): Float = (440.0 * 2.0.pow((midi - 69.0) / 12.0)).toFloat()
    fun noteName(midi: Int): String = NOTE_NAMES[((midi % 12) + 12) % 12]
    /** Octave en notation française (La3 = 440 Hz). */
    fun octave(midi: Int): Int = Math.floorDiv(midi, 12) - 2
}

enum class Scale(val label: String, val intervals: IntArray) {
    CHROMATIC("Chromatique", intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)),
    MAJOR("Majeure", intArrayOf(0, 2, 4, 5, 7, 9, 11)),
    MINOR("Mineure naturelle", intArrayOf(0, 2, 3, 5, 7, 8, 10)),
    HARMONIC_MINOR("Mineure harmonique", intArrayOf(0, 2, 3, 5, 7, 8, 11)),
    PENTA_MAJOR("Pentatonique majeure", intArrayOf(0, 2, 4, 7, 9)),
    PENTA_MINOR("Pentatonique mineure", intArrayOf(0, 3, 5, 7, 10)),
    BLUES("Blues", intArrayOf(0, 3, 5, 6, 7, 10)),
    DORIAN("Dorien", intArrayOf(0, 2, 3, 5, 7, 9, 10)),
}

/** Trouve la note de la gamme la plus proche, avec hystérésis pour éviter le « tremblement ». */
class ScaleQuantizer {
    private val allowed = BooleanArray(12) { true }

    fun set(key: Int, scale: Scale) {
        allowed.fill(false)
        for (iv in scale.intervals) allowed[(key + iv) % 12] = true
    }

    fun isInScale(midi: Int) = allowed[((midi % 12) + 12) % 12]

    fun nearest(midi: Float, previous: Int = Int.MIN_VALUE, hysteresis: Float = 0.15f): Int {
        val base = midi.roundToInt()
        var best = base
        var bestDist = Float.MAX_VALUE
        for (d in -6..6) {
            val n = base + d
            if (!isInScale(n)) continue
            val dist = abs(midi - n)
            if (dist < bestDist) {
                bestDist = dist
                best = n
            }
        }
        if (previous != Int.MIN_VALUE && previous != best && isInScale(previous)) {
            if (abs(midi - previous) - bestDist < hysteresis) return previous
        }
        return best
    }
}
