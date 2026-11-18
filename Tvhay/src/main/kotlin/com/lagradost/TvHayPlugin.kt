package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TvHayPlugin : Plugin() {
    override fun load(context: Context) {
        ClipboardLogger.init(context)
        TvHayProvider.appContext = context.applicationContext ?: context
        registerMainAPI(TvHayProvider())
    }
}
