cloudstream {
    description = "MoviesPro provider with stage-by-stage accumulated clipboard diagnostics"
    authors = listOf("OpenAI")
    status = 1
    version = 4
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
}
dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    // Nếu code/thư viện dùng @Nullable của JetBrains:
    compileOnly("org.jetbrains:annotations:24.1.0")
}
android { namespace = "com.lagradost" }
