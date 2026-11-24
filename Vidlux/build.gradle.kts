plugins {
    kotlin("android")
}

cloudstream {
    language = "en"
    description = "Vidlux (vidlux.xyz) - Movies & TV Series với hệ thống đa server Vidlux, giải mã AES-GCM, WebView sniffer, OpenSubtitles & SubDL"
    authors = listOf("silentmist3590")
    status = 1
    version = 3
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
}

android {
    namespace = "com.lagradost"
}
