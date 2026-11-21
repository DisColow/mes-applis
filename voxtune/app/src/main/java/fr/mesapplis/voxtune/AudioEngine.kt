package fr.mesapplis.voxtune

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import fr.mesapplis.voxtune.dsp.AutoTuneProcessor
import fr.mesapplis.voxtune.dsp.WavWriter
import java.io.File

/** Boucle micro → correction → casque, sur un thread audio prioritaire. */
class AudioEngine(audioManager: AudioManager) {
    val sampleRate: Int =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
    private val framesPerBuffer: Int =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256

    val processor = AutoTuneProcessor(sampleRate)

    @Volatile var monitoring = true
    @Volatile var running = false; private set
    private var thread: Thread? = null

    private val recLock = Any()
    private var wav: WavWriter? = null

    var onError: ((String) -> Unit)? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "VoxTuneAudio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
        stopRecording()
    }

    fun startRecording(file: File) = synchronized(recLock) {
        wav?.close()
        wav = WavWriter(file, sampleRate)
    }

    /** Termine l'enregistrement et renvoie le fichier (ou null). */
    fun stopRecording(): WavWriter? = synchronized(recLock) {
        val w = wav
        wav = null
        w?.close()
        w
    }

    val isRecording: Boolean get() = synchronized(recLock) { wav != null }

    @SuppressLint("MissingPermission")
    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val block = framesPerBuffer.coerceIn(64, 512)
        val inFmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val outFmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        var record: AudioRecord? = null
        var track: AudioTrack? = null
        try {
            val minIn = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
            )
            record = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(inFmt)
                .setBufferSizeInBytes(maxOf(minIn, block * 4 * 4))
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) error("Micro indisponible")

            val minOut = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(outFmt)
                .setBufferSizeInBytes(maxOf(minOut, block * 4 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            processor.reset()
            val buf = FloatArray(block)
            val silence = FloatArray(block)
            record.startRecording()
            track.play()

            while (running) {
                val n = record.read(buf, 0, block, AudioRecord.READ_BLOCKING)
                if (n <= 0) {
                    if (n < 0) error("Erreur de lecture micro ($n)")
                    continue
                }
                processor.process(buf, n)
                synchronized(recLock) { wav?.write(buf, n) }
                track.write(if (monitoring) buf else silence, 0, n, AudioTrack.WRITE_BLOCKING)
            }
        } catch (t: Throwable) {
            running = false
            onError?.invoke(t.message ?: t.toString())
        } finally {
            try { record?.stop() } catch (_: Throwable) {}
            try { track?.stop() } catch (_: Throwable) {}
            record?.release()
            track?.release()
        }
    }
}
