package com.discolow.cadeauxgo.bot

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.discolow.cadeauxgo.vision.Pattern
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Écran d'apprentissage affiché par-dessus le jeu : la capture est figée, l'utilisateur
 * entoure un élément au doigt (et peut toucher un point pour dire où cliquer), puis choisit
 * ce que c'est.
 */
class TeachOverlay(
    private val ctx: Context,
    private val wm: WindowManager,
    private val shot: Bitmap,
    private val onClose: () -> Unit,
) {
    private val root = FrameLayout(ctx)
    private val view = ShotView(ctx)
    private val hint = TextView(ctx)
    private val bottom = LinearLayout(ctx)
    private var closed = false

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).roundToInt()

    fun show() {
        root.setBackgroundColor(Color.BLACK)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

        hint.setTextColor(Color.WHITE)
        hint.textSize = 15f
        hint.setBackgroundColor(0xCC000000.toInt())
        hint.setPadding(dp(12), dp(36), dp(12), dp(10))
        hint.text = "Entoure au doigt le bouton ou l'élément à reconnaître.\n" +
            "Puis, si besoin, touche (sans glisser) l'endroit où cliquer — sinon : le centre."
        root.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        bottom.orientation = LinearLayout.VERTICAL
        bottom.setBackgroundColor(0xDD000000.toInt())
        bottom.setPadding(dp(8), dp(8), dp(8), dp(40))
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        showStep1()

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        )
        wm.addView(root, lp)
    }

    fun dismiss() {
        if (closed) return
        closed = true
        runCatching { wm.removeView(root) }
        onClose()
    }

    private fun btn(text: String, onClick: () -> Unit) = Button(ctx).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun showStep1() {
        bottom.removeAllViews()
        hint.visibility = View.VISIBLE
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("Annuler") { dismiss() }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(btn("Suivant →") {
            val r = view.rect
            if (r == null) {
                toast("Entoure d'abord un élément")
            } else if (r.width() < 16 || r.height() < 16) {
                toast("Zone trop petite, entoure un peu plus large")
            } else {
                showStep2()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(row)
    }

    private fun showStep2() {
        bottom.removeAllViews()
        hint.visibility = View.GONE
        val title = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            text = "Qu'est-ce que c'est ?"
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        bottom.addView(title)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (role in Role.UI_ORDER) {
            val b = btn(role.label) { save(role) }
            list.addView(b)
            list.addView(TextView(ctx).apply {
                setTextColor(0xFFBBBBBB.toInt())
                textSize = 12f
                text = role.help
                setPadding(dp(8), 0, dp(8), dp(10))
            })
        }
        val scroll = ScrollView(ctx)
        scroll.addView(list)
        val h = (ctx.resources.displayMetrics.heightPixels * 0.55f).toInt()
        bottom.addView(scroll, LinearLayout.LayoutParams(-1, h))
        bottom.addView(btn("← Retour") { showStep1() })
    }

    private fun save(role: Role) {
        val r = view.rect ?: return
        val x = r.left.toInt().coerceIn(0, shot.width - 1)
        val y = r.top.toInt().coerceIn(0, shot.height - 1)
        val w = r.width().toInt().coerceAtMost(shot.width - x)
        val h = r.height().toInt().coerceAtMost(shot.height - y)
        val crop = Bitmap.createBitmap(shot, x, y, w, h)
        val px = IntArray(w * h)
        crop.getPixels(px, 0, w, 0, 0, w, h)
        if (Pattern(w, h, px).std < 6.0) {
            toast("Zone trop uniforme : entoure un élément avec du texte ou un dessin")
            crop.recycle()
            return
        }
        val tap = view.tap
        val tapX = (tap?.first ?: (r.centerX())) - x
        val tapY = (tap?.second ?: (r.centerY())) - y
        TemplateStore.add(ctx, role, crop, x, y, tapX.roundToInt(), tapY.roundToInt(), shot.width, shot.height)
        crop.recycle()
        val n = TemplateStore.all(ctx).count { it.role == role }
        toast("Appris : ${role.label} ($n)")
        dismiss()
    }

    private fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show()

    /** Affiche la capture ; les coordonnées exposées sont celles de la capture (pixels réels). */
    private inner class ShotView(c: Context) : View(c) {
        var rect: RectF? = null
            private set
        var tap: Pair<Float, Float>? = null
            private set

        private var scale = 1f
        private var offX = 0f
        private var offY = 0f
        private var downX = 0f
        private var downY = 0f
        private var dragging = false

        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dim = Paint().apply { color = 0x88000000.toInt() }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat(); color = 0xFF00E676.toInt()
        }
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat(); color = 0xFFFF4081.toInt()
        }

        private fun layoutImage() {
            scale = min(width.toFloat() / shot.width, height.toFloat() / shot.height)
            offX = (width - shot.width * scale) / 2
            offY = (height - shot.height * scale) / 2
        }

        private fun toImg(x: Float, y: Float) =
            ((x - offX) / scale).coerceIn(0f, shot.width.toFloat()) to ((y - offY) / scale).coerceIn(0f, shot.height.toFloat())

        override fun onDraw(canvas: Canvas) {
            if (shot.isRecycled) return
            layoutImage()
            canvas.save()
            canvas.translate(offX, offY)
            canvas.scale(scale, scale)
            canvas.drawBitmap(shot, 0f, 0f, bmpPaint)
            canvas.restore()
            val r = rect ?: return
            val vr = RectF(offX + r.left * scale, offY + r.top * scale, offX + r.right * scale, offY + r.bottom * scale)
            canvas.drawRect(0f, 0f, width.toFloat(), vr.top, dim)
            canvas.drawRect(0f, vr.bottom, width.toFloat(), height.toFloat(), dim)
            canvas.drawRect(0f, vr.top, vr.left, vr.bottom, dim)
            canvas.drawRect(vr.right, vr.top, width.toFloat(), vr.bottom, dim)
            canvas.drawRect(vr, stroke)
            val (tx, ty) = tap ?: (r.centerX() to r.centerY())
            val cx = offX + tx * scale
            val cy = offY + ty * scale
            val s = dp(12).toFloat()
            canvas.drawLine(cx - s, cy, cx + s, cy, cross)
            canvas.drawLine(cx, cy - s, cx, cy + s, cross)
            canvas.drawCircle(cx, cy, s * 0.6f, cross)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && (abs(e.x - downX) > dp(10) || abs(e.y - downY) > dp(10))) {
                        dragging = true
                        tap = null
                    }
                    if (dragging) {
                        val (x1, y1) = toImg(downX, downY)
                        val (x2, y2) = toImg(e.x, e.y)
                        rect = RectF(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging && rect != null) {
                        tap = toImg(e.x, e.y)
                        invalidate()
                    }
                }
            }
            return true
        }
    }
}
