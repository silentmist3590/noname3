package com.movieshdhub

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MoviesHDHubPlugin : Plugin() {
    override fun load(context: Context) {
        Log.e("MoviesHDHub", "Bắt đầu load plugin...")
        try {
            val api = MoviesHDHub()
            registerMainAPI(api)
            Log.e("MoviesHDHub", "Đăng ký API THÀNH CÔNG: ${api.name}")
        } catch (t: Throwable) {
            Log.e("MoviesHDHub", "ĐĂNG KÝ THẤT BẠI: ${t.message}", t)
        }
    }
}
