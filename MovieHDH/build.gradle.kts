plugins {
    id("com.android.library")
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    language = "en"
    description = "MoviesHDHub - Watch FREE Movies Online HD"
    authors = listOf("TJ-Plugins")
    version = 3 // Tăng version lên 2 để mod nhận diện là bản cập nhật
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    // Không cần iconUrl
}

android {
    namespace = "com.movieshdhub"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // BẮT BUỘC PHẢI LÀ compileOnly, không dùng implementation
    compileOnly("com.github.recloudstream:cloudstream:-SNAPSHOT")
    implementation("org.jsoup:jsoup:1.17.2")
}
