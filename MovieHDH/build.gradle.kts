plugins {
    id("com.android.library")
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    language = "en"
    description = "MoviesHDHub - Watch FREE Movies Online HD"
    authors = listOf("TJ-Plugins")
    version = 2
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
}

android {
    namespace = "com.movieshdhub"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Tự động đồng bộ JVM Target cho cả Java và Kotlin
kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly("com.github.recloudstream:cloudstream:-SNAPSHOT")
    implementation("org.jsoup:jsoup:1.17.2")
}
