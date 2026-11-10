include(":Superembed")


pluginManagement {
    repositories {
        // ⭐ Thêm Aliyun mirror để vượt lỗi 504
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // build.gradle.kts ở gốc khai báo repositories trong allprojects{} (có jitpack),
    // nên phải cho phép repository ở cấp project. FAIL_ON_PROJECT_REPOS sẽ làm build lỗi.
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        // ⭐ Thêm Aliyun mirror để vượt lỗi 504
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
rootProject.name = "CloudstreamPlugins"
