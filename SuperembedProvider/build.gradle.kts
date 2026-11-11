// use an integer for version numbers
version = 19

cloudstream {
    description = "Superembed.stream - Movie and TV"
    authors = listOf("silentmist3590")
    status = 1
    language = "en"
    tvTypes = listOf("Movie", "TVseries")
    requiresResources = false
}

android {
    namespace = "com.lagradost"
}
dependencies {
    // Thư viện coroutines bắt buộc cho WebView Extractor
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
