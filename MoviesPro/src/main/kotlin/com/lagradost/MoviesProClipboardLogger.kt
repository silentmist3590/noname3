package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lagradost.cloudstream3.CloudStreamApp

/** Accumulates diagnostics for the current operation and copies the whole buffer after each stage. */
object MoviesProClipboardLogger {
    private const val TAG = "MoviesProDiag"
    private const val LIMIT = 55_000
    private val buffer = StringBuilder()

    @Synchronized fun reset(label: String = "session") {
        buffer.setLength(0)
        appendLocked("SESSION", label)
    }

    fun stage(stage: String, message: String) = append(stage, message, false)
    fun error(stage: String, message: String, throwable: Throwable? = null) {
        append("ERROR/$stage", message + (throwable?.let { "\n${it.javaClass.simpleName}: ${it.message}\n${it.stackTraceToString().take(2500)}" } ?: ""), true)
    }

    @Synchronized private fun append(stage: String, message: String, isError: Boolean) {
        val safe = redact(message).take(9000)
        appendLocked(stage, safe)
        if (isError) Log.e(TAG, "[$stage] $safe") else Log.d(TAG, "[$stage] $safe")
        copyLocked()
    }

    private fun appendLocked(stage: String, message: String) {
        buffer.append("\n--- [").append(stage).append("] ---\n").append(message).append('\n')
        if (buffer.length > LIMIT) buffer.delete(0, buffer.length - LIMIT)
    }

    private fun redact(s: String): String = s
        .replace(Regex("(?i)(token|key|signature|sig|auth|authorization|cookie)=([^&\\s]+)"), "$1=<redacted>")
        .replace(Regex("(?i)(Bearer\\s+)[A-Za-z0-9._~-]+"), "$1<redacted>")

    private fun copyLocked() {
        try {
            val ctx = CloudStreamApp.context ?: return
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val snapshot = buffer.toString()
            Handler(Looper.getMainLooper()).post {
                try { cm.setPrimaryClip(ClipData.newPlainText("MoviesPro diagnostics", snapshot)) }
                catch (e: Exception) { Log.e(TAG, "Clipboard copy failed", e) }
            }
        } catch (e: Exception) { Log.e(TAG, "Clipboard unavailable", e) }
    }
}
