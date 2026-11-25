package com.discolow.cadeauxgo.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

/** Couleurs de l'appli (clair / sombre). */
class Palette(dark: Boolean) {
    val bg = if (dark) 0xFF121016.toInt() else 0xFFF4F1FA.toInt()
    val surface = if (dark) 0xFF1E1B24.toInt() else 0xFFFFFFFF.toInt()
    val text = if (dark) 0xFFECE8F4.toInt() else 0xFF1C1B22.toInt()
    val muted = if (dark) 0xFFA29DB0.toInt() else 0xFF6B6878.toInt()
    val outline = if (dark) 0xFF34303D.toInt() else 0xFFE6E0F0.toInt()
    val primary = if (dark) 0xFFB39DDB.toInt() else 0xFF7E57C2.toInt()
    val onPrimary = if (dark) 0xFF1C1033.toInt() else 0xFFFFFFFF.toInt()
    val pink = 0xFFEC407A.toInt()
    val green = if (dark) 0xFF81C784.toInt() else 0xFF2E7D32.toInt()
    val teal = if (dark) 0xFF4DB6AC.toInt() else 0xFF00897B.toInt()
    val red = if (dark) 0xFFEF9A9A.toInt() else 0xFFC62828.toInt()
    val orange = if (dark) 0xFFFFB74D.toInt() else 0xFFE65100.toInt()
    val warnBg = if (dark) 0xFF3A2A12.toInt() else 0xFFFFF4E5.toInt()
    val warnText = if (dark) 0xFFFFCC80.toInt() else 0xFF8A4B08.toInt()
    val codeBg = if (dark) 0xFF2A2633.toInt() else 0xFFF1EDF8.toInt()

    companion object {
        fun of(ctx: Context) = Palette(
            (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
        )
    }
}

/** Petits constructeurs de vues stylées (sans bibliothèque). */
class Ui(private val ctx: Context, val c: Palette) {

    fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).roundToInt()

    fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        strokeColor?.let { setStroke(dp(1), it) }
    }

    private fun ripple(bg: GradientDrawable, rippleColor: Int) = RippleDrawable(ColorStateList.valueOf(rippleColor), bg, null)

    fun vertical(block: LinearLayout.() -> Unit = {}) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; block() }
    fun horizontal(block: LinearLayout.() -> Unit = {}) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; block()
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, bottom: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    fun text(t: CharSequence, size: Float = 15f, color: Int = c.text, bold: Boolean = false) = TextView(ctx).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(0f, 1.15f)
    }

    fun muted(t: CharSequence, size: Float = 13f) = text(t, size, c.muted)

    /** Pastille d'état (ex. « Service actif »). */
    fun chip(t: String, fg: Int, bg: Int) = text(t, 13f, fg, bold = true).apply {
        background = rounded(bg, 50)
        setPadding(dp(12), dp(5), dp(12), dp(5))
    }

    /** Carte arrondie avec titre (icône + nom) ; [collapsible] = repliable, état mémorisé. */
    /** Pastille ronde en dégradé de la charte, avec une icône blanche. */
    fun badge(icon: Int, colors: IntArray, sizeDp: Int = 38) = android.widget.ImageView(ctx).apply {
        setImageResource(icon)
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        val pad = dp(sizeDp) / 5
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { shape = GradientDrawable.OVAL }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { rightMargin = dp(14) }
    }

    fun card(
        parent: LinearLayout,
        icon: Int,
        colors: IntArray,
        title: String,
        subtitle: String? = null,
        collapsible: Boolean = false,
        expanded: Boolean = true,
        onToggle: (Boolean) -> Unit = {},
    ): LinearLayout {
        val card = vertical {
            background = rounded(c.surface, 20, c.outline)
            elevation = dp(1).toFloat()
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        val head = horizontal()
        head.addView(badge(icon, colors))
        val titles = vertical()
        titles.addView(text(title, 18f, bold = true))
        subtitle?.let { titles.addView(muted(it)) }
        head.addView(titles, lp(0, weight = 1f))
        val body = vertical()
        if (collapsible) {
            val chevron = text(if (expanded) "▾" else "▸", 20f, c.muted)
            head.addView(chevron)
            body.visibility = if (expanded) View.VISIBLE else View.GONE
            head.setOnClickListener {
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
                chevron.text = if (open) "▾" else "▸"
                onToggle(open)
            }
        }
        card.addView(head)
        card.addView(body, lp(top = 8))
        parent.addView(card, lp(top = 14))
        return body
    }

    /** Bouton plein (dégradé violet → rose). */
    fun primaryButton(t: String, onClick: () -> Unit) = text(t, 15f, Color.WHITE, bold = true).apply {
        gravity = Gravity.CENTER
        val bg = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF7E57C2.toInt(), c.pink)).apply {
            cornerRadius = dp(26).toFloat()
        }
        background = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), bg, null)
        setPadding(dp(18), dp(13), dp(18), dp(13))
        setOnClickListener { onClick() }
    }

    /** Bouton tonal (fond teinté, texte coloré). */
    fun tonalButton(t: String, color: Int = c.primary, onClick: () -> Unit) = text(t, 14f, color, bold = true).apply {
        gravity = Gravity.CENTER
        background = ripple(rounded((color and 0x00FFFFFF) or 0x1F000000, 22), (color and 0x00FFFFFF) or 0x33000000)
        setPadding(dp(16), dp(11), dp(16), dp(11))
        setOnClickListener { onClick() }
    }

    /** Ligne : libellé (+ aide) à gauche, interrupteur à droite. */
    @Suppress("DEPRECATION")
    fun switchRow(label: String, help: String?, value: Boolean, onChange: (Boolean) -> Unit): View {
        val row = horizontal { setPadding(0, dp(8), 0, dp(8)) }
        val texts = vertical()
        texts.addView(text(label, 15f))
        help?.let { texts.addView(muted(it, 12.5f)) }
        row.addView(texts, lp(0, weight = 1f))
        val sw = Switch(ctx).apply {
            isChecked = value
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(c.primary, if (c.bg == 0xFF121016.toInt()) 0xFF8E8A99.toInt() else Color.WHITE))
            trackTintList = ColorStateList(states, intArrayOf((c.primary and 0x00FFFFFF) or 0x66000000, c.outline))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return row
    }

    /** Ligne : libellé à gauche, petit champ numérique à droite. */
    fun numberRow(label: String, help: String?, value: Int, onChange: (Int) -> Unit): View {
        val row = horizontal { setPadding(0, dp(6), 0, dp(6)) }
        val texts = vertical()
        texts.addView(text(label, 15f))
        help?.let { texts.addView(muted(it, 12.5f)) }
        row.addView(texts, lp(0, weight = 1f))
        row.addView(EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(c.text)
            background = rounded(c.codeBg, 12, c.outline)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addTextChangedListener(watcher { s -> s.toIntOrNull()?.takeIf { it > 0 }?.let(onChange) })
        }, lp(dp(96)))
        return row
    }

    /** Champ texte multi-ligne avec libellé au-dessus. */
    fun textArea(label: String, value: String, lines: Int, onChange: (String) -> Unit): View {
        val box = vertical { setPadding(0, dp(8), 0, dp(4)) }
        box.addView(text(label, 14f, c.muted))
        box.addView(EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = lines
            gravity = Gravity.TOP or Gravity.START
            textSize = 14f
            setTextColor(c.text)
            setText(value)
            background = rounded(c.codeBg, 12, c.outline)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addTextChangedListener(watcher(onChange))
        }, lp(top = 6))
        return box
    }

    /** Curseur avec libellé et valeur. */
    fun slider(label: String, min: Int, max: Int, value: Int, unit: String, help: String?, onChange: (Int) -> Unit): View {
        val box = vertical { setPadding(0, dp(8), 0, dp(4)) }
        val head = horizontal()
        head.addView(text(label, 15f), lp(0, weight = 1f))
        val v = text("$value$unit", 15f, c.primary, bold = true)
        head.addView(v)
        box.addView(head)
        box.addView(SeekBar(ctx).apply {
            this.min = min
            this.max = max
            progress = value.coerceIn(min, max)
            progressTintList = ColorStateList.valueOf(c.primary)
            thumbTintList = ColorStateList.valueOf(c.primary)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    v.text = "$p$unit"
                    if (fromUser) onChange(p)
                }

                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }, lp(top = 4))
        help?.let { box.addView(muted(it, 12.5f)) }
        return box
    }

    /** Encadré d'avertissement. */
    fun warning(t: String) = text(t, 13.5f, c.warnText).apply {
        background = rounded(c.warnBg, 14)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    /** Étapes numérotées (pastille ronde + texte). */
    fun step(n: Int, t: CharSequence): View {
        val row = horizontal { gravity = Gravity.TOP; setPadding(0, dp(6), 0, dp(6)) }
        row.addView(text("$n", 13f, c.onPrimary, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(c.primary, 50)
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) })
        row.addView(text(t, 14.5f), lp(0, weight = 1f))
        return row
    }

    fun divider() = View(ctx).apply { setBackgroundColor(c.outline) }

    private fun watcher(onChange: (String) -> Unit) = object : android.text.TextWatcher {
        override fun afterTextChanged(s: android.text.Editable?) = onChange(s?.toString() ?: "")
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    }
}
