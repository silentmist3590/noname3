// use an integer for version numbers
version = 9

cloudstream {
    description = "Movieuniverse - movieuniverse.skin (phim lẻ & TV, nhiều server, phụ đề)"
    authors = listOf("silentmist3590")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "en"
}

android {
    namespace = "com.movieuniverse"
}
dependencies {
    // ... các dependency khác ...
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
