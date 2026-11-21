package fr.mesapplis.voxtune.dsp

import kotlin.math.min

/** Prise de voix brute en mémoire (mono float), stockée par morceaux d'une seconde. */
class Take(val sampleRate: Int) {
    private val chunk = sampleRate
    private val chunks = ArrayList<FloatArray>()

    @Volatile var length = 0
        private set

    val durationSeconds: Float get() = length.toFloat() / sampleRate

    fun append(src: FloatArray, n: Int) {
        var i = 0
        var len = length
        while (i < n) {
            val c = len / chunk
            if (c == chunks.size) chunks.add(FloatArray(chunk))
            val off = len % chunk
            val k = min(n - i, chunk - off)
            System.arraycopy(src, i, chunks[c], off, k)
            i += k
            len += k
        }
        length = len
    }

    /** Copie jusqu'à [max] échantillons depuis [pos] ; renvoie le nombre copié. */
    fun read(pos: Int, dst: FloatArray, max: Int): Int {
        val n = min(max, length - pos)
        if (n <= 0 || pos < 0) return 0
        var i = 0
        var p = pos
        while (i < n) {
            val c = p / chunk
            val off = p % chunk
            val k = min(n - i, chunk - off)
            System.arraycopy(chunks[c], off, dst, i, k)
            i += k
            p += k
        }
        return n
    }

    /** Applique la correction à toute la prise et écrit le résultat. */
    fun render(processor: AutoTuneProcessor, out: WavWriter) {
        processor.reset()
        val buf = FloatArray(512)
        var pos = 0
        while (true) {
            val n = read(pos, buf, buf.size)
            if (n <= 0) break
            processor.process(buf, n)
            out.write(buf, n)
            pos += n
        }
    }
}
