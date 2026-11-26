package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.CommonActivity

object ClipboardLogger {
    private const val TAG = "VidZen"
    private const val MAX_LINES = 500
    private const val MAX_LINE_LENGTH = 300
    private const val CLIP_LABEL = "VidZen Log"

    private val buffer = mutableListOf<String>()

    fun logAndCopy(tag: String, message: String) {
        val safe = if (message.length > MAX_LINE_LENGTH) message.take(MAX_LINE_LENGTH) + "…" else message
        val line = "[$tag] $safe"
        buffer.add(line)
        if (buffer.size > MAX_LINES) buffer.subList(0, 100).clear()
        Log.d(TAG, line)

        val ctx = CommonActivity.activity ?: return
        try {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, buffer.joinToString("\n")))
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
