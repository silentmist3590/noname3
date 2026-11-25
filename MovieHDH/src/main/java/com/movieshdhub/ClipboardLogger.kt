package com.movieshdhub

import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import com.lagradost.cloudstream3.CommonActivity

object ClipboardLogger {
    private const val TAG = "MoviesHDHub"
    private val buffer = mutableListOf<String>()

    fun logAndCopy(tag: String, message: String) {
        val line = "[$tag] $message"
        buffer.add(line)
        Log.d(TAG, line)
        val clipboard = CommonActivity.activity?.getSystemService(ClipboardManager::class.java) ?: return
        try {
            clipboard.setPrimaryClip(
                ClipData.newPlainText("MoviesHDHub Log", buffer.joinToString("\n"))
            )
        } catch (t: Throwable) {
            Log.e(TAG, "clipboard write failed: ${t.message}")
        }
    }

    fun reset() {
        buffer.clear()
        val clipboard = CommonActivity.activity?.getSystemService(ClipboardManager::class.java) ?: return
        try {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (t: Throwable) {
            Log.e(TAG, "clipboard reset failed: ${t.message}")
        }
    }
}
