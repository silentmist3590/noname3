package com.movieuniverse

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

object ClipboardLogger {
    private const val TAG = "PluginLogger"
    private const val MAX_CHARS = 60_000

    /** true = luôn copy log vào clipboard; false = chỉ copy khi tiêu đề chứa "Error"/"Exception". */
    private const val COPY_ALWAYS = true

    private val buffer = StringBuilder()

    /** Lấy Context mà không phụ thuộc lớp CloudStreamApp/AcraApplication (tên đổi theo phiên bản app). */
    fun appContext(): Context? {
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
    fun reset() {
        buffer.setLength(0)
    }

    @Synchronized
    fun logAndCopy(title: String, message: String) {
        val entry = "=== [$title] ===\n$message\n"
        Log.d(TAG, entry)
        buffer.append(entry).append('\n')
        if (buffer.length > MAX_CHARS) buffer.delete(0, buffer.length - MAX_CHARS)
        if (!COPY_ALWAYS && !title.contains("Error", true) && !title.contains("Exception", true)) return
        val text = buffer.toString()
        try {
            val context = appContext() ?: return
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            Handler(Looper.getMainLooper()).post {
                try {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Plugin Log", text))
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to copy to clipboard", e)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to copy to clipboard", e)
        }
    }
}
