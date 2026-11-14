package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lagradost.cloudstream3.CommonActivity

object ClipboardLogger {
    private var appContext: Context? = null
    private val buffer = StringBuilder()

    fun init(context: Context) {
        appContext = context.applicationContext ?: context
    }

    private fun getContext(): Context? {
        if (appContext != null) return appContext
        val act = CommonActivity.activity
        if (act != null) {
            appContext = act.applicationContext
            return appContext
        }
        try {
            val at = Class.forName("android.app.ActivityThread")
            val method = at.getMethod("currentApplication")
            val app = method.invoke(null) as? Context
            if (app != null) {
                appContext = app
                return appContext
            }
        } catch (_: Exception) { }
        return null
    }

    fun logAndCopy(tag: String, message: String) {
        Log.d("VidSrcFyi-Log", "[$tag] $message")
        synchronized(buffer) {
            buffer.append("[$tag] $message\n")
            val fullText = buffer.toString()
            val ctx = getContext() ?: return
            try {
                Handler(Looper.getMainLooper()).post {
                    try {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("vidsrc_fyi_logs", fullText))
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }
    }

    fun reset() {
        synchronized(buffer) {
            buffer.setLength(0)
        }
    }
}
