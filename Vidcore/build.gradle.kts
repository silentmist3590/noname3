plugins {
    kotlin("android")
}

cloudstream {
    language = "en"
    description = "Vidcore.org - Movies & TV Series with ?autoplay=true WebView sniffer, OpenSubtitles & SubDL"
    authors = listOf("TJ-Plugins")
    status = 1
    version = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://vidcore.org/favicon.svg"
}
android { namespace = "com.lagradost" }
