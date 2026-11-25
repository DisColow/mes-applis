package com.discolow.cadeauxgo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Rend l'écran principal en images PNG dans build/preview. Lancer avec PREVIEW=1. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi")
class PreviewTest {

    private fun render(name: String, expandAll: Boolean = false) {
        assumeTrue(System.getenv("PREVIEW") == "1")
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        if (expandAll) {
            activity.getSharedPreferences("ui", 0).edit().putBoolean("learnedOpen", true).putBoolean("advancedOpen", true).commit()
        }
        val content = (activity.window.decorView.findViewById<View>(android.R.id.content))
        val w = content.width.takeIf { it > 0 } ?: 1200
        val spec = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
        val scroll = (content as android.view.ViewGroup).getChildAt(0) as android.view.ViewGroup
        val inner = scroll.getChildAt(0)
        inner.measure(spec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        inner.layout(0, 0, inner.measuredWidth, inner.measuredHeight)
        val bmp = Bitmap.createBitmap(inner.measuredWidth, inner.measuredHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        (scroll.background)?.let { it.setBounds(0, 0, bmp.width, bmp.height); it.draw(canvas) }
        inner.draw(canvas)
        val out = File("build/preview").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun clair() = render("clair")

    @Test
    @Config(sdk = [34], qualifiers = "+night")
    fun sombre() = render("sombre")

    @Test
    fun clairDeplie() {
        assumeTrue(System.getenv("PREVIEW") == "1")
        RuntimeEnvironment.getApplication().getSharedPreferences("ui", 0).edit()
            .putBoolean("learnedOpen", true).putBoolean("advancedOpen", true).commit()
        render("clair_deplie")
    }

    @Test
    fun panneau() {
        assumeTrue(System.getenv("PREVIEW") == "1")
        val svc = Robolectric.buildService(com.discolow.cadeauxgo.bot.BotService::class.java).create().get()
        com.discolow.cadeauxgo.bot.BotService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(svc)
        val f = com.discolow.cadeauxgo.bot.BotService::class.java.getDeclaredField("panel").apply { isAccessible = true }
        val panel = f.get(svc) as View
        panel.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        // Le panneau doit contenir toute la ligne de boutons (bug : dernier bouton coupé).
        val row = (panel as android.view.ViewGroup).getChildAt(0) as android.view.ViewGroup
        val needed = (0 until row.childCount).sumOf {
            val ch = row.getChildAt(it)
            val lp = ch.layoutParams as android.view.ViewGroup.MarginLayoutParams
            ch.measuredWidth + lp.leftMargin + lp.rightMargin
        }
        org.junit.Assert.assertTrue("ligne ${row.measuredWidth} < $needed", row.measuredWidth >= needed)
        org.junit.Assert.assertTrue(panel.measuredWidth >= needed + panel.paddingLeft + panel.paddingRight)
        val pad = 40
        val bmp = Bitmap.createBitmap(panel.measuredWidth + 2 * pad, panel.measuredHeight + 2 * pad, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFFDCEFD9.toInt()) // fond vert clair, comme le jeu
        canvas.translate(pad.toFloat(), pad.toFloat())
        panel.draw(canvas)
        val out = File("build/preview").apply { mkdirs() }
        File(out, "panneau.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun icone() {
        assumeTrue(System.getenv("PREVIEW") == "1")
        val ctx = RuntimeEnvironment.getApplication()
        val size = 432
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // L'icône adaptative applique elle-même le masque du système et l'agrandissement de ses calques.
        val d = ctx.getDrawable(com.discolow.cadeauxgo.R.mipmap.ic_launcher)!!
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        val out = File("build/preview").apply { mkdirs() }
        File(out, "icone.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
