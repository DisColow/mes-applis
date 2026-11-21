package fr.mesapplis.voxtune.dsp

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Chaîne de traitement indépendante d'Android : détection → quantification → transposition.
 * Traite des blocs de float mono.
 */
class AutoTuneProcessor(private val sampleRate: Int) {
    // Analyse sur un signal décimé par 2 pour réduire le coût CPU.
    private val anaSize = 1024
    private val anaRing = FloatArray(anaSize)
    private var anaPos = 0
    private val anaFrame = FloatArray(anaSize)
    private var decimPrev = 0f
    private var decimToggle = false
    private val hop = 512
    private var hopCounter = 0
    private val detector = PitchDetector(sampleRate / 2f, anaSize)

    private val shifter = PitchShifter(sampleRate.toFloat())
    private val quantizer = ScaleQuantizer()

    // Paramètres (modifiables depuis l'UI)
    @Volatile var key = 0
    @Volatile var scale = Scale.CHROMATIC
    @Volatile var retuneMs = 20f
    @Volatile var amount = 1f
    @Volatile var transpose = 0
    @Volatile var inputGain = 1f
    @Volatile var outputGain = 1f
    @Volatile var enabled = true

    // État publié pour l'affichage
    @Volatile var detectedMidi = Float.NaN; private set
    @Volatile var targetMidi = Int.MIN_VALUE; private set
    @Volatile var outputMidi = Float.NaN; private set
    @Volatile var inputLevel = 0f; private set

    private var appliedKey = -1
    private var appliedScale: Scale? = null
    private var lastTarget = Int.MIN_VALUE
    private var targetCorrection = 0f
    private var correction = 0f
    private var currentRatio = 1f
    private var unvoicedSamples = 0

    fun reset() {
        shifter.reset()
        anaRing.fill(0f)
        correction = 0f; targetCorrection = 0f; currentRatio = 1f
        lastTarget = Int.MIN_VALUE
        detectedMidi = Float.NaN; outputMidi = Float.NaN; targetMidi = Int.MIN_VALUE
    }

    fun process(buf: FloatArray, n: Int) {
        if (appliedKey != key || appliedScale != scale) {
            appliedKey = key; appliedScale = scale
            quantizer.set(key, scale)
            lastTarget = Int.MIN_VALUE
        }
        val gIn = inputGain
        var peak = 0f
        for (i in 0 until n) {
            val x = buf[i] * gIn
            buf[i] = x
            val ax = if (x < 0) -x else x
            if (ax > peak) peak = ax
            if (decimToggle) {
                anaRing[anaPos] = 0.5f * (x + decimPrev)
                anaPos = (anaPos + 1) and (anaSize - 1)
            }
            decimPrev = x
            decimToggle = !decimToggle
        }
        inputLevel = peak

        hopCounter += n
        if (hopCounter >= hop) {
            hopCounter = 0
            analyze()
        }

        // Lissage de la correction (vitesse de retune)
        val blockMs = n * 1000f / sampleRate
        val alpha = if (retuneMs <= 0.5f) 1f else 1f - exp(-blockMs / (retuneMs / 3f))
        correction += (targetCorrection - correction) * alpha

        val semis = (if (enabled) correction * amount else 0f) + transpose
        val newRatio = 2f.pow(semis / 12f)
        val step = (newRatio - currentRatio) / n
        val gOut = outputGain
        for (i in 0 until n) {
            currentRatio += step
            val y = shifter.process(buf[i], currentRatio) * gOut
            buf[i] = if (y > 0.8f || y < -0.8f) tanh(y) else y // écrêtage doux
        }
        currentRatio = newRatio
    }

    private fun analyze() {
        var idx = anaPos
        for (i in 0 until anaSize) {
            anaFrame[i] = anaRing[idx]
            idx = (idx + 1) and (anaSize - 1)
        }
        val hz = detector.detect(anaFrame)
        if (hz > 0f) {
            unvoicedSamples = 0
            val midi = Music.hzToMidi(hz)
            val target = quantizer.nearest(midi, lastTarget)
            lastTarget = target
            targetCorrection = target - midi
            detectedMidi = midi
            shifter.setPeriod(sampleRate / hz)
            targetMidi = target
            outputMidi = midi + (if (enabled) correction * amount else 0f) + transpose
        } else {
            unvoicedSamples += hop
            if (unvoicedSamples > sampleRate / 5) {
                // Silence prolongé : on relâche doucement la correction
                targetCorrection = 0f
                lastTarget = Int.MIN_VALUE
                detectedMidi = Float.NaN
                outputMidi = Float.NaN
                targetMidi = Int.MIN_VALUE
                shifter.setPeriod(0f)
            }
        }
    }
}
