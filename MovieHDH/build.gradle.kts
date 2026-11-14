import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    language = "en"
    description = "MoviesHDHub - Watch FREE Movies Online HD"
    authors = listOf("TJ-Plugins")
    version = 3
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    apiName = "MoviesHDHub"
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

// Đồng bộ JVM Target cho Kotlin compiler
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly("com.github.recloudstream:cloudstream:-SNAPSHOT")
    implementation("org.jsoup:jsoup:1.17.2")
}
