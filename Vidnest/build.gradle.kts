plugins {
    kotlin("android")
}

cloudstream {
    language = "en"
    description = "VidNest (vidnext.pro) - Movies & TV Series with Catalog API, Nova/Zenith/Comet/Raven HLS & DASH servers, and multi-language subtitles"
    authors = listOf("TJ-Plugins")
    status = 1
    version = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://vidnext.pro/favicon.svg"
}
android { namespace = "com.lagradost" }
