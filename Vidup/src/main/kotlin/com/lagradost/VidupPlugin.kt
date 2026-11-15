package com.lagradost

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.io.File

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
        val currentAutoDownload = prefs.getBoolean(VidupSubtitleStore.KEY_AUTO_DOWNLOAD, true)

        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val infoView = TextView(ctx).apply {
            text = "OpenSubtitles hoạt động miễn phí không cần key.\n" +
                "SubDL cần API Key miễn phí tại: https://subdl.com/panel/api"
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

        val autoDownloadCheck = CheckBox(ctx).apply {
            text = "Tự động tải phụ đề về máy (file .srt/.vtt cục bộ)"
            isChecked = currentAutoDownload
            setPadding(0, pad / 2, 0, 0)
        }

        container.addView(infoView)
        container.addView(keyLabel)
        container.addView(keyInput)
        container.addView(autoDownloadCheck)

        AlertDialog.Builder(ctx)
            .setTitle("Cài đặt phụ đề Vidup (OpenSubtitles & SubDL)")
            .setView(container)
            .setPositiveButton("Lưu") { _, _ ->
                prefs.edit()
                    .putString(VidupSubtitleStore.KEY_SUBDL_API, keyInput.text.toString().trim())
                    .putBoolean(VidupSubtitleStore.KEY_AUTO_DOWNLOAD, autoDownloadCheck.isChecked)
                    .apply()
                Toast.makeText(ctx, "Đã lưu cài đặt phụ đề Vidup", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Xóa sub đã tải") { _, _ ->
                val deleted = VidupSubtitleStore.clearDownloadedSubtitles(ctx)
                Toast.makeText(ctx, "Đã xóa $deleted file phụ đề đã tải", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }
}

object VidupSubtitleStore {
    const val PREFS_NAME = "vidup_subtitle_prefs"
    const val KEY_SUBDL_API = "subdl_api_key"
    const val KEY_AUTO_DOWNLOAD = "auto_download_subs"

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext ?: context
    }

    fun getAppContext(): Context? = appContext

    fun getSubtitlesDir(context: Context? = appContext): File? {
        val ctx = context ?: return null
        val ext = runCatching { ctx.getExternalFilesDir("Subtitles") }.getOrNull()
        val dir = ext ?: File(ctx.cacheDir, "vidup_subs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun clearDownloadedSubtitles(context: Context? = appContext): Int {
        val ctx = context ?: return 0
        var count = 0
        val dirs = listOfNotNull(
            runCatching { ctx.getExternalFilesDir("Subtitles") }.getOrNull(),
            File(ctx.cacheDir, "vidup_subs")
        )
        for (d in dirs) {
            if (d.exists() && d.isDirectory) {
                d.listFiles()?.forEach { f ->
                    if (f.isFile && f.delete()) count++
                }
            }
        }
        return count
    }
}
