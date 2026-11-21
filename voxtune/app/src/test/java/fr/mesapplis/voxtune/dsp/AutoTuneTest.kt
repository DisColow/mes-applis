package fr.mesapplis.voxtune.dsp

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AutoTuneTest {
    private val sr = 48000

    /** Signal riche en harmoniques, proche d'une voix. */
    private fun voice(hz: Double, seconds: Double): FloatArray {
        val n = (sr * seconds).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / sr
            var s = 0.0
            for (h in 1..6) s += sin(2 * PI * hz * h * t) / h
            (0.3 * s).toFloat()
        }
    }

    private fun measure(x: FloatArray, from: Int): Float {
        val det = PitchDetector(sr.toFloat(), 2048)
        return det.detect(x.copyOfRange(from, from + 2048))
    }

    @Test
    fun detectorFindsPitch() {
        for (hz in listOf(98.0, 220.0, 440.0, 660.0)) {
            val f = measure(voice(hz, 0.2), 1000)
            assertEquals(hz, f.toDouble(), hz * 0.005)
        }
    }

    @Test
    fun quantizerSnapsToScale() {
        val q = ScaleQuantizer().apply { set(0, Scale.MAJOR) }
        assertEquals(60, q.nearest(60.4f))   // Do
        assertEquals(62, q.nearest(61.2f))   // Do# hors gamme → Ré
        assertEquals(64, q.nearest(63.6f))
        assertEquals(60, q.nearest(60.6f, previous = 60)) // hystérésis
    }

    private fun runProcessor(inHz: Double, configure: AutoTuneProcessor.() -> Unit): Float {
        val p = AutoTuneProcessor(sr).apply(configure)
        val x = voice(inHz, 1.5)
        val block = 256
        var i = 0
        while (i + block <= x.size) {
            val b = x.copyOfRange(i, i + block)
            p.process(b, block)
            b.copyInto(x, i)
            i += block
        }
        // médiane de plusieurs mesures après 1 s (évite les fenêtres de fondu)
        val m = (0 until 9).map { measure(x, sr - 4000 + it * 997) }.sorted()
        return m[4]
    }

    @Test
    fun correctsOffKeyNote() {
        // 226 Hz (La trop haut de ~47 cents) → en Do majeur doit revenir sur La (220 Hz)
        val outHz = runProcessor(226.0) { key = 0; scale = Scale.MAJOR; retuneMs = 0f }
        assertEquals(220.0, outHz.toDouble(), 1.5)
    }

    @Test
    fun transposes() {
        val outHz = runProcessor(220.0) { scale = Scale.CHROMATIC; transpose = 12 }
        assertEquals(440.0, outHz.toDouble(), 3.0)
    }
}
