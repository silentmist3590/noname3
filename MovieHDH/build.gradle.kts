plugins {
    kotlin("android")
}

cloudstream {
    language = "en"
    description = "MoviesHDHub - Watch FREE Movies Online HD"
    authors = listOf("TJ-Plugins")
    version = 14
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://movieshdhub.com/favicon.ico"
}

dependencies {
    // kotlinx.coroutines không còn đi kèm cloudstream.jar (dùng cho suspendCancellableCoroutine ở WebView)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}

android {
    namespace = "com.movieshdhub"
}
