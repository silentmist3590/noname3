plugins {
    id("com.android.library")
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    language = "en"
    description = "VidZen - Next-Gen Streaming API"
    authors = listOf("YourName")
    version = 6
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
}

android {
    namespace = "com.vidzen"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
    }
}

dependencies {
    compileOnly("com.github.recloudstream:cloudstream:-SNAPSHOT")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
}
