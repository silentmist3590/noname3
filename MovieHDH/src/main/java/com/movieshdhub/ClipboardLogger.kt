package com.movieshdhub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.CommonActivity

/**
 * Ghi log + tự động copy toàn bộ buffer vào Clipboard.
 * Golden Rule #1: chỉ dùng CommonActivity.activity — KHÔNG dùng CloudStreamApp / app.context.
 * Golden Rule #3: có logAndCopy + reset.
 */
object ClipboardLogger {

    private const val TAG = "MoviesHDHub"
    private val buffer = mutableListOf<String>()

    fun logAndCopy(tag: String, message: String) {
        val line = "[$tag] $message"
        buffer.add(line)
        Log.d(TAG, line)
        val ctx = CommonActivity.activity ?: return
        try {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(
                    ClipData.newPlainText("MoviesHDHub Log", buffer.joinToString("\n"))
                )
        } catch (t: Throwable) {
            Log.e(TAG, "clipboard write failed: ${t.message}")
        }
    }

    fun reset() {
        buffer.clear()
        val ctx = CommonActivity.activity ?: return
        try {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (t: Throwable) {
            Log.e(TAG, "clipboard reset failed: ${t.message}")
        }
    }
}
