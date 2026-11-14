plugins {
    kotlin("android")
}

cloudstream {
    language = "en"
    description = "MoviesHDHub - Watch FREE Movies Online HD"
    authors = listOf("TJ-Plugins")
    version = 8
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://movieshdhub.com/favicon.ico"
}

android {
    namespace = "com.movieshdhub"
}
