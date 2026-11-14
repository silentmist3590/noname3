import com.android.build.gradle.LibraryExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.gradle.api.tasks.Delete
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// Sửa BaseExtension thành LibraryExtension để nhận diện compileSdk
fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = 
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()
    
fun Project.android(configuration: LibraryExtension.() -> Unit) = 
    extensions.getByName<LibraryExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "silentmist3590/noname3")
    }

    android {
        // ĐÃ BỎ namespace Ở ĐÂY ĐỂ NHƯỜNG QUYỀN CHO TỪNG MODULE (tránh ghi đè com.movieshdhub)
        
        compileSdk = 35

        defaultConfig {
            minSdk = 21
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }

    // Đồng bộ JVM Target cho Kotlin
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-Xno-call-assertions",
                "-Xno-param-assertions",
                "-Xno-receiver-assertions"
            )
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations
        
        cloudstream("com.lagradost:cloudstream3:pre-release")
        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.18")
        implementation("com.squareup.okhttp3:okhttp:4.12.0")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
