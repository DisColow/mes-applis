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
import fr.mesapplis.voxtune.dsp.Take
import fr.mesapplis.voxtune.dsp.WavWriter
import java.io.File

/**
 * Moteur audio, sur un thread prioritaire. Trois modes :
 *  - LIVE     : micro → correction → casque
 *  - CAPTURE  : micro → prise brute en mémoire (aucune sortie, donc pas de larsen)
 *  - PLAYBACK : prise → correction (réglages modifiables en direct) → haut-parleur/casque
 */
class AudioEngine(audioManager: AudioManager) {
    enum class Mode { IDLE, LIVE, CAPTURE, PLAYBACK }

    val sampleRate: Int =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
    private val block: Int =
        (audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256)
            .coerceIn(64, 512)

    val processor = AutoTuneProcessor(sampleRate)
    val maxTakeSeconds = 180

    @Volatile var mode = Mode.IDLE; private set
    val running: Boolean get() = mode != Mode.IDLE
    @Volatile private var stopRequested = false
    private var thread: Thread? = null

    @Volatile var monitoring = true

    // Mode Studio
    @Volatile var take: Take? = null; private set
    @Volatile var playPos = 0; private set
    @Volatile var seekRequest = -1
    @Volatile var playOriginal = false
    @Volatile var looping = false

    private val recLock = Any()
    private var wav: WavWriter? = null

    /** Appelé (depuis le thread audio) quand le moteur s'arrête de lui-même ou sur erreur. */
    var onEnded: ((error: String?) -> Unit)? = null

    fun startLive() = launch(Mode.LIVE) { liveLoop() }
    fun startCapture() = launch(Mode.CAPTURE) { captureLoop() }

    fun startPlayback(): Boolean {
        val t = take ?: return false
        if (t.length == 0) return false
        if (playPos >= t.length) playPos = 0
        launch(Mode.PLAYBACK) { playLoop(t) }
        return true
    }

    fun stop() {
        stopRequested = true
        thread?.join(1500)
        thread = null
        stopRecording()
        mode = Mode.IDLE
    }

    private fun launch(m: Mode, body: () -> Unit) {
        if (running) stop()
        stopRequested = false
        mode = m
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var err: String? = null
            try {
                body()
            } catch (t: Throwable) {
                err = t.message ?: t.toString()
            }
            val selfEnded = !stopRequested
            mode = Mode.IDLE
            if (selfEnded) onEnded?.invoke(err)
        }, "VoxTuneAudio").also { it.start() }
    }

    // ------------------------------------------------------------ enregistrement WAV (mode direct)

    fun startRecording(file: File) = synchronized(recLock) {
        wav?.close()
        wav = WavWriter(file, sampleRate)
    }

    fun stopRecording(): WavWriter? = synchronized(recLock) {
        val w = wav
        wav = null
        w?.close()
        w
    }

    val isRecording: Boolean get() = synchronized(recLock) { wav != null }

    // ------------------------------------------------------------ boucles

    private fun liveLoop() {
        val record = openRecord()
        val track = openTrack()
        try {
            processor.reset()
            val buf = FloatArray(block)
            val silence = FloatArray(block)
            record.startRecording(); track.play()
            while (!stopRequested) {
                val n = readMic(record, buf)
                if (n == 0) continue
                processor.process(buf, n)
                synchronized(recLock) { wav?.write(buf, n) }
                track.write(if (monitoring) buf else silence, 0, n, AudioTrack.WRITE_BLOCKING)
            }
        } finally {
            close(record, track)
        }
    }

    private fun captureLoop() {
        val record = openRecord()
        try {
            val t = Take(sampleRate)
            take = t
            playPos = 0
            processor.reset()
            val buf = FloatArray(block)
            val scratch = FloatArray(block)
            val max = maxTakeSeconds * sampleRate
            record.startRecording()
            while (!stopRequested && t.length < max) {
                val n = readMic(record, buf)
                if (n == 0) continue
                t.append(buf, n)
                // Analyse seulement, pour afficher la hauteur pendant la prise
                System.arraycopy(buf, 0, scratch, 0, n)
                processor.process(scratch, n)
            }
        } finally {
            close(record, null)
        }
    }

    private fun playLoop(t: Take) {
        val track = openTrack()
        try {
            processor.reset()
            val raw = FloatArray(block)
            val buf = FloatArray(block)
            track.play()
            while (!stopRequested) {
                val seek = seekRequest
                if (seek >= 0) {
                    seekRequest = -1
                    playPos = seek.coerceIn(0, t.length)
                    processor.reset()
                }
                val n = t.read(playPos, raw, block)
                if (n <= 0) {
                    if (looping) { playPos = 0; processor.reset(); continue }
                    playPos = t.length
                    track.write(FloatArray(block * 4), 0, block * 4, AudioTrack.WRITE_BLOCKING)
                    break
                }
                System.arraycopy(raw, 0, buf, 0, n)
                processor.process(buf, n)
                track.write(if (playOriginal) raw else buf, 0, n, AudioTrack.WRITE_BLOCKING)
                playPos += n
            }
        } finally {
            close(null, track)
        }
    }

    // ------------------------------------------------------------ utilitaires

    private fun readMic(record: AudioRecord, buf: FloatArray): Int {
        val n = record.read(buf, 0, block, AudioRecord.READ_BLOCKING)
        if (n < 0) error("Erreur de lecture micro ($n)")
        return n
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(): AudioRecord {
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val min = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val r = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(min, block * 4 * 4))
            .build()
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            error("Micro indisponible")
        }
        return r
    }

    private fun openTrack(): AudioTrack {
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val min = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(min, block * 4 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private fun close(record: AudioRecord?, track: AudioTrack?) {
        try { record?.stop() } catch (_: Throwable) {}
        try { track?.stop() } catch (_: Throwable) {}
        record?.release()
        track?.release()
    }
}
