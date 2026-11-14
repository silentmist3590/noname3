package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Logger cho MoviesPro: ghi log vào bộ nhớ đệm và copy toàn bộ vào clipboard sau mỗi dòng. */
object MoviesProClipboardLogger {
    private const val TAG = "MoviesProLogger"
    private const val MAX_CHARS = 60_000
    private val buffer = StringBuilder()

    private fun appContext(): Context? {
        try {
            val at = Class.forName("android.app.ActivityThread")
            val app = at.getMethod("currentApplication").invoke(null) as? Context
            if (app != null) return app
        } catch (_: Throwable) {
        }
        for (cls in listOf("com.lagradost.cloudstream3.CloudStreamApp", "com.lagradost.cloudstream3.AcraApplication")) {
            try {
                val c = Class.forName(cls)
                val comp = c.getField("Companion").get(null)
                val ctx = comp.javaClass.getMethod("getContext").invoke(comp) as? Context
                if (ctx != null) return ctx
            } catch (_: Throwable) {
            }
        }
        return null
    }

    @Synchronized
    fun reset(title: String = "") {
        buffer.setLength(0)
        if (title.isNotBlank()) stage("START", title)
    }

    @Synchronized
    fun stage(title: String, message: String) {
        val entry = "=== [$title] ===\n$message\n"
        Log.d(TAG, entry)
        buffer.append(entry).append('\n')
        if (buffer.length > MAX_CHARS) buffer.delete(0, buffer.length - MAX_CHARS)
        copy()
    }

    @Synchronized
    fun error(title: String, message: String, e: Throwable? = null) {
        stage("ERROR $title", message + (if (e != null) "\n" + e.stackTraceToString().take(1200) else ""))
    }

    private fun copy() {
        val text = buffer.toString()
        try {
            val context = appContext() ?: return
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            Handler(Looper.getMainLooper()).post {
                try {
                    clipboard.setPrimaryClip(ClipData.newPlainText("MoviesPro Log", text))
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to copy to clipboard", t)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to copy to clipboard", t)
        }
    }
}
