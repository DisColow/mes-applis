package com.discolow.cadeauxgo.bot

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Garde la trace du dernier plantage pour l'afficher dans l'appli. */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "dernier_plantage.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, previous))
    }

    private class Handler(val ctx: Context, val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val date = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date())
                file(ctx).writeText("$date (thread ${t.name})\n$sw")
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun read(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.readText()
    fun clear(ctx: Context) {
        file(ctx).delete()
    }
}
