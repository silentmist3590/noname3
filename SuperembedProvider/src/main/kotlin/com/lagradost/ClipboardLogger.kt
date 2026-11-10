package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.CommonActivity

object ClipboardLogger {
    private const val TAG = "SuperembedLogger"
    private val logBuilder = StringBuilder()

    private fun getContext(): Context? {
        return try { CommonActivity.activity } catch (e: Throwable) { null }
    }

    fun logAndCopy(title: String, message: String) {
        // Nối thêm log mới vào biến lưu trữ
        logBuilder.append("=== [$title] ===\n$message\n\n")
        
        // Giới hạn độ dài để không bị tràn RAM (khoảng 5000 ký tự)
        if (logBuilder.length > 5000) {
            logBuilder.delete(0, logBuilder.length - 5000)
        }

        val fullLog = logBuilder.toString()
        Log.d(TAG, fullLog)

        try {
            val ctx = getContext() ?: return
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Plugin Log", fullLog)
            clipboard.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to clipboard", e)
        }
    }

    fun reset() {
        logBuilder.setLength(0) // Xoá sạch bộ nhớ log cũ
        try {
            val ctx = getContext() ?: return
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset clipboard: ${e.message}")
        }
    }
}
