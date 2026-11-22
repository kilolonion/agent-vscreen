plugins {
    id("com.android.application")
}

android {
    namespace = "com.kilon.avstorture"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.kilon.avstorture"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
