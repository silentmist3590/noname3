package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lagradost.cloudstream3.CommonActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ClipboardLogger {
    private var appContext: Context? = null
    private val buffer = StringBuilder()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        appContext = context.applicationContext ?: context
    }

    private fun getContext(): Context? {
        if (appContext != null) return appContext
        val act = CommonActivity.activity
        if (act != null) {
            appContext = act.applicationContext ?: act
            return appContext
        }
        try {
            val at = Class.forName("android.app.ActivityThread")
            val method = at.getMethod("currentApplication")
            val app = method.invoke(null) as? Context
            if (app != null) {
                appContext = app.applicationContext ?: app
                return appContext
            }
        } catch (_: Exception) { }
        return null
    }

    fun log(tag: String, message: String) {
        logAndCopy(tag, message)
    }

    fun logAndCopy(tag: String, message: String) {
        val ts = synchronized(timeFmt) { timeFmt.format(Date()) }
        val line = "[$ts][$tag] $message"
        Log.d("TvHay-Log", line)
        synchronized(buffer) {
            if (buffer.length > 24_000) {
                buffer.delete(0, buffer.length - 12_000)
            }
            buffer.append(line).append('\n')
            val fullText = buffer.toString()
            val ctx = getContext() ?: return
            try {
                Handler(Looper.getMainLooper()).post {
                    try {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("tvhay_logs", fullText))
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
