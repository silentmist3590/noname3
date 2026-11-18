// TvHay/build.gradle.kts
cloudstream {
    description = "TvHay (tvhay.buzz) provider với hỗ trợ đa server (PhimAPI, StreamVSMov, StreamC) và auto-copy debug logs"
    authors = listOf("silentmist3590")
    status = 1
    version = 1
    tvTypes = listOf("Movie", "TvSeries", "AsianDrama", "Anime")
    requiresResources = false
}

android {
    namespace = "com.lagradost"
}
