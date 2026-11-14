plugins {
    kotlin("android")
}

cloudstream {
    language = "en"
    description = "Movieshdhub.com"
    authors = listOf("TJ-Plugins")
    version = 1
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
}
android {
    namespace = "com.movieshdhub"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }
}

dependencies {
    implementation("com.github.recloudstream:cloudstream:-SNAPSHOT")
    implementation("org.jsoup:jsoup:1.17.2")
}
