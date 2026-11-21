package fr.mesapplis.voxtune.dsp

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Écrit un fichier WAV PCM 16 bits mono. */
class WavWriter(val file: File, private val sampleRate: Int) {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private var dataBytes = 0L
    private var scratch = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)

    init {
        out.write(ByteArray(44)) // en-tête complété à la fermeture
    }

    fun write(samples: FloatArray, count: Int) {
        if (scratch.capacity() < count * 2) {
            scratch = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        }
        scratch.clear()
        for (i in 0 until count) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            scratch.putShort(v.toShort())
        }
        out.write(scratch.array(), 0, count * 2)
        dataBytes += count * 2
    }

    fun close() {
        out.close()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes.toInt())
        }
        RandomAccessFile(file, "rw").use { it.seek(0); it.write(header.array()) }
    }

    val durationSeconds: Float get() = dataBytes / 2f / sampleRate
}
