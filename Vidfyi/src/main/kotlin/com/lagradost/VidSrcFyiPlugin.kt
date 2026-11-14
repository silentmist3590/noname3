package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VidSrcFyiPlugin : Plugin() {
    override fun load(context: Context) {
        ClipboardLogger.init(context)
        registerMainAPI(VidSrcFyiProvider())
    }
}
