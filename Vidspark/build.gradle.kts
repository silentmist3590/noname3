plugins {
    kotlin("android")
}

cloudstream {
    language = "en"
    description = "Vidspark.to - Movies & TV Series with direct multi-server API, autoplay WebView sniffer, built-in subtitles, OpenSubtitles & SubDL"
    authors = listOf("TJ-Plugins")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://vidspark.to/favicon.ico"
}
android { namespace = "com.lagradost" }
