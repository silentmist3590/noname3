plugins {
    kotlin("android")
}

cloudstream {
    language = "en"
    description = "Vidup.to - Movies & TV Series with autoPlay WebView stream sniffer, multi-server failover & Vietnamese/English subtitles"
    authors = listOf("TJ-Plugins")
    version = 2
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://vidup.to/favicon.ico"
}
android { namespace = "com.lagradost" }
