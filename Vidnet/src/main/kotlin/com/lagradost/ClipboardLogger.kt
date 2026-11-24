package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.CommonActivity

/** Clipboard + Logcat diagnostics for CloudStream 4.5.x. */
object ClipboardLogger {
    private const val TAG = "VidnetDiag"
    private const val MAX_CHARS = 45_000
    private val lock = Any()
    private val buffer = StringBuilder()

    fun clear() {
        synchronized(lock) {
            buffer.setLength(0)
        }
        Log.d(TAG, "Diagnostic buffer cleared")
    }

    fun logAndCopy(title: String, message: String) {
        val entry = "=== [$title] ===\n${redact(message).take(9_000)}\n===================\n"
        synchronized(lock) {
            buffer.append(entry).append('\n')
            if (buffer.length > MAX_CHARS) {
                buffer.delete(0, buffer.length - MAX_CHARS)
            }
        }
        Log.d(TAG, entry)

        val activity = try {
            CommonActivity.activity
        } catch (e: Throwable) {
            Log.e(TAG, "Cannot obtain CommonActivity", e)
            null
        }

        if (activity == null) {
            Log.w(TAG, "CLIPBOARD SKIPPED: CommonActivity.activity is null")
            return
        }

        activity.runOnUiThread {
            try {
                val snapshot = synchronized(lock) { buffer.toString() }
                val clipboard = activity.getSystemService(
                    Context.CLIPBOARD_SERVICE
                ) as? ClipboardManager
                if (clipboard == null) {
                    Log.e(TAG, "ClipboardManager unavailable")
                    return@runOnUiThread
                }
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("Vidnet diagnostics", snapshot)
                )
                Log.d(TAG, "CLIPBOARD UPDATED: ${snapshot.length} characters")
            } catch (e: Exception) {
                Log.e(TAG, "Clipboard copy failed", e)
            }
        }
    }

    private fun redact(input: String): String {
        return input
            .replace(
                Regex("""(?i)(api_token|token|authorization|cookie)=([^&\s"'<>]+)"""),
                "$1=<redacted>"
            )
            .replace(
                Regex("""(?i)(Bearer\s+)[A-Za-z0-9._~-]+"""),
                "$1<redacted>"
            )
    }
}
