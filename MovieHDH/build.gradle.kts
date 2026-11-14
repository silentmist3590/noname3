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
    // iconUrl không bắt buộc — bỏ để tránh lỗi filter
}

android {
    namespace = "com.movieshdhub"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        // targetSdk KHÔNG khai báo trong library — AGP 8 deprecate rồi
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Kotlin compilerOptions DSL mới (thay cho kotlinOptions.jvmTarget)
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // BẮT BUỘC: compileOnly cho CloudStream core
    compileOnly("com.github.recloudstream:cloudstream:-SNAPSHOT")
    implementation("org.jsoup:jsoup:1.17.2")
}
