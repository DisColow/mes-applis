package fr.mesapplis.voxtune

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import fr.mesapplis.voxtune.dsp.Music
import fr.mesapplis.voxtune.dsp.ScaleQuantizer

/** Courbe défilante : hauteur chantée (gris) et hauteur corrigée (couleur), sur la grille de la gamme. */
class PitchGraphView(context: Context) : View(context) {
    private val capacity = 300
    private val raw = FloatArray(capacity) { Float.NaN }
    private val corrected = FloatArray(capacity) { Float.NaN }
    private var head = 0
    private var center = 60f
    var quantizer: ScaleQuantizer? = null
    var keyRoot = 0

    private val d = resources.displayMetrics.density
    private val gridPaint = Paint().apply { color = 0x22FFFFFF; strokeWidth = 1f * d }
    private val rootPaint = Paint().apply { color = 0x55FF4FA3.toInt(); strokeWidth = 1.5f * d }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt(); textSize = 10f * d
    }
    private val rawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88B0B0C0.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * d
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val outPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4FA3"); style = Paint.Style.STROKE; strokeWidth = 3.5f * d
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()

    fun push(rawMidi: Float, outMidi: Float) {
        raw[head] = rawMidi
        corrected[head] = outMidi
        head = (head + 1) % capacity
        if (!outMidi.isNaN()) center += (outMidi - center) * 0.05f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF15131C.toInt())
        val w = width.toFloat()
        val h = height.toFloat()
        val range = 14f
        val top = center + range / 2
        fun y(m: Float) = (top - m) / range * h

        val q = quantizer
        val left = 34f * d
        for (m in (center - range / 2).toInt() - 1..(center + range / 2).toInt() + 1) {
            if (q != null && !q.isInScale(m)) continue
            val yy = y(m.toFloat())
            val isRoot = ((m - keyRoot) % 12 + 12) % 12 == 0
            canvas.drawLine(left, yy, w, yy, if (isRoot) rootPaint else gridPaint)
            canvas.drawText(Music.noteName(m), 4f * d, yy + 4f * d, labelPaint)
        }
        drawSeries(canvas, raw, rawPaint, left, w, ::y)
        drawSeries(canvas, corrected, outPaint, left, w, ::y)
    }

    private inline fun drawSeries(
        canvas: Canvas, data: FloatArray, paint: Paint, left: Float, w: Float, y: (Float) -> Float
    ) {
        path.reset()
        var penDown = false
        val dx = (w - left) / (capacity - 1)
        for (i in 0 until capacity) {
            val v = data[(head + i) % capacity]
            if (v.isNaN()) { penDown = false; continue }
            val x = left + i * dx
            if (penDown) path.lineTo(x, y(v)) else path.moveTo(x, y(v))
            penDown = true
        }
        canvas.drawPath(path, paint)
    }
}
