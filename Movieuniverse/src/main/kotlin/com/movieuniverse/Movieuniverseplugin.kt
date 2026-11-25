package com.movieuniverse

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovieuniversePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MovieuniverseProvider())
    }
}
