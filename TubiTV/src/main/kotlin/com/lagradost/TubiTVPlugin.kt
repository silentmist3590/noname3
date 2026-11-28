package com.lagradost

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TubiTVPlugin : Plugin() {
    override fun load(context: Context) {
        ClipboardLogger.init(context)
        TubiTVSettingsStore.init(context)
        registerMainAPI(TubiTVProvider())

        openSettings = { activityCtx ->
            showTubiSettingsDialog(activityCtx)
        }
    }

    private fun showTubiSettingsDialog(ctx: Context) {
        val prefs = ctx.getSharedPreferences(TubiTVSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
        val currentRelay = prefs.getString(TubiTVSettingsStore.KEY_RELAY_URL, "") ?: ""
        val currentProxy = prefs.getString(TubiTVSettingsStore.KEY_PROXY_ADDR, "") ?: ""
        val currentSubdlKey = prefs.getString(TubiTVSettingsStore.KEY_SUBDL_API, "") ?: ""

        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val infoView = TextView(ctx).apply {
            text = "TubiTV chặn IP ngoài vùng hỗ trợ (US/CA/UK/AU).\n" +
                "• Khuyên dùng: Bật VPN sang Mỹ (US) hoặc Canada trên Android.\n" +
                "• Hoặc cấu hình Proxy/Relay US riêng bên dưới (tùy chọn).\n" +
                "• Phụ đề tự động nạp 2 bản Việt + 1 bản Anh từ OpenSubtitles & SubDL."
            textSize = 12.5f
            setPadding(0, 0, 0, pad / 2)
        }

        val relayLabel = TextView(ctx).apply {
            text = "Relay / Worker URL (tùy chọn, dùng khi không bật VPN):"
            textSize = 13f
            setPadding(0, pad / 4, 0, 0)
        }
        val relayInput = EditText(ctx).apply {
            hint = "VD: https://my-tubi-relay.workers.dev/?url="
            setText(currentRelay)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }

        val proxyLabel = TextView(ctx).apply {
            text = "HTTP / SOCKS5 Proxy (host:port, tùy chọn):"
            textSize = 13f
            setPadding(0, pad / 2, 0, 0)
        }
        val proxyInput = EditText(ctx).apply {
            hint = "VD: 127.0.0.1:1080 hoặc socks://127.0.0.1:1080"
            setText(currentProxy)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }

        val subdlLabel = TextView(ctx).apply {
            text = "SubDL API Key (tùy chọn - lấy miễn phí tại subdl.com/panel/api):"
            textSize = 13f
            setPadding(0, pad / 2, 0, 0)
        }
        val subdlInput = EditText(ctx).apply {
            hint = "Dán SubDL API Key..."
            setText(currentSubdlKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
        }

        container.addView(infoView)
        container.addView(relayLabel)
        container.addView(relayInput)
        container.addView(proxyLabel)
        container.addView(proxyInput)
        container.addView(subdlLabel)
        container.addView(subdlInput)

        val scroll = ScrollView(ctx).apply { addView(container) }

        AlertDialog.Builder(ctx)
            .setTitle("Cài đặt TubiTV (Proxy & Phụ đề)")
            .setView(scroll)
            .setPositiveButton("Lưu") { _, _ ->
                prefs.edit()
                    .putString(TubiTVSettingsStore.KEY_RELAY_URL, relayInput.text.toString().trim())
                    .putString(TubiTVSettingsStore.KEY_PROXY_ADDR, proxyInput.text.toString().trim())
                    .putString(TubiTVSettingsStore.KEY_SUBDL_API, subdlInput.text.toString().trim())
                    .apply()
                Toast.makeText(ctx, "Đã lưu cài đặt TubiTV", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }
}

object TubiTVSettingsStore {
    const val PREFS_NAME = "tubitv_plugin_prefs"
    const val KEY_RELAY_URL = "tubi_relay_url"
    const val KEY_PROXY_ADDR = "tubi_proxy_addr"
    const val KEY_SUBDL_API = "subdl_api_key"

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext ?: context
    }

    fun getAppContext(): Context? = appContext
}
