package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SuperembedPlugin : Plugin() {
    override fun load(context: Context) {
        // Đăng ký Provider vào hệ thống
        registerMainAPI(SuperembedProvider())
    }
}
