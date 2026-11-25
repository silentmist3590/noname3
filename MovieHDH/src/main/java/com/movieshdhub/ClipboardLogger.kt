package com.movieshdhub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.CommonActivity

object ClipboardLogger {
    private const val TAG = "MoviesHDHub"
    private val buffer = mutableListOf<String>()

    private fun copy(text: String) {
        try {
            val ctx: Context? = CommonActivity.activity
            val cm = ctx?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("MoviesHDHub Log", text))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "clipboard write failed: ${t.message}")
        }
    }

    @Synchronized
    fun logAndCopy(tag: String, message: String) {
        val line = "[$tag] $message"
        buffer.add(line)
        Log.d(TAG, line)
        copy(buffer.joinToString("\n"))
    }

    @Synchronized
    fun reset() {
        buffer.clear()
        copy("")
    }
}
