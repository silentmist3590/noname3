package com.lagradost

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VidupPlugin : Plugin() {
    override fun load(context: Context) {
        ClipboardLogger.init(context)
        VidupSubtitleStore.init(context)
        registerMainAPI(VidupProvider())

        openSettings = { activityCtx ->
            showSubtitleSettingsDialog(activityCtx)
        }
    }

    private fun showSubtitleSettingsDialog(ctx: Context) {
        val prefs = ctx.getSharedPreferences(VidupSubtitleStore.PREFS_NAME, Context.MODE_PRIVATE)
        val currentKey = prefs.getString(VidupSubtitleStore.KEY_SUBDL_API, "") ?: ""

        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val infoView = TextView(ctx).apply {
            text = "Phụ đề hiển thị trực tiếp theo danh sách (chỉ tải khi bạn chọn phát).\n" +
                "• OpenSubtitles: hoạt động miễn phí không cần API Key.\n" +
                "• SubDL (tùy chọn): lấy API Key miễn phí tại https://subdl.com/panel/api"
            textSize = 13f
            setPadding(0, 0, 0, pad / 2)
        }

        val keyLabel = TextView(ctx).apply {
            text = "SubDL API Key:"
            textSize = 14f
        }

        val keyInput = EditText(ctx).apply {
            hint = "Dán SubDL API Key tại đây..."
            setText(currentKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
        }

        container.addView(infoView)
        container.addView(keyLabel)
        container.addView(keyInput)

        AlertDialog.Builder(ctx)
            .setTitle("Cài đặt phụ đề Vidup (OpenSubtitles & SubDL)")
            .setView(container)
            .setPositiveButton("Lưu") { _, _ ->
                prefs.edit()
                    .putString(VidupSubtitleStore.KEY_SUBDL_API, keyInput.text.toString().trim())
                    .apply()
                Toast.makeText(ctx, "Đã lưu SubDL API Key", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }
}

object VidupSubtitleStore {
    const val PREFS_NAME = "vidup_subtitle_prefs"
    const val KEY_SUBDL_API = "xHeOWlCfS9o4EKmK7OwbeOBIhfbIHr2Y"

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext ?: context
    }

    fun getAppContext(): Context? = appContext
}
