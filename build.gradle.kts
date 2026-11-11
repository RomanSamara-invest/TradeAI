plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ru.tradeai.moex"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.tradeai.moex"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }
}
