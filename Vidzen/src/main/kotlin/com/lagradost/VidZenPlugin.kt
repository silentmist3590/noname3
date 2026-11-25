package com.vidzen

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class VidZenPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(VidZenProvider())
    }
}
