import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val avsUnsignedRelease = providers.gradleProperty("avsUnsignedRelease").map { it.toBooleanStrict() }.orElse(false).get()

android {
    namespace = "com.kilon.agentvscreen"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.kilon.agentvscreen"
        minSdk = 30
        targetSdk = 35
        versionCode = (project.findProperty("avsVersionCode") as String?)?.toIntOrNull() ?: 75
        versionName = "2.0.1-beta1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // Unsigned CI builds do not even read local keystore settings. Local release builds keep existing behavior.
    val ksProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (!avsUnsignedRelease && f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (ksProps.getProperty("storeFile") != null) create("release") {
            storeFile = rootProject.file(ksProps.getProperty("storeFile"))
            storePassword = ksProps.getProperty("storePassword")
            keyAlias = ksProps.getProperty("keyAlias")
            keyPassword = ksProps.getProperty("keyPassword")
            // v1 (JAR) as well as v2/v3: some vendor installers and scanners still read the JAR certificate.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        // 2.0: the test build also carries x86_64 native code, so OCR on the CI emulator (x86_64) runs natively instead
        // of through the arm64 translator (0.5-30 s a reading there). Release stays arm64 only.
        debug { ndk { abiFilters += listOf("x86_64") } }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (avsUnsignedRelease) null else signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { compose = true; buildConfig = true }
    androidResources { noCompress += "onnx" }
    // The built-in Tailscale node is an executable shipped as lib*.so: it must be unpacked into nativeLibraryDir.
    packaging { jniLibs { useLegacyPackaging = true } }
    lint {
        abortOnError = true
        error += "Aligned16KB"
    }
    // 1.1.7: tests assert the Chinese texts; the system language picks them (English tests set I18n.choice).
    testOptions { unitTests.isReturnDefaultValues = true; unitTests.isIncludeAndroidResources = true
        unitTests.all { it.jvmArgs("-Duser.language=zh", "-Duser.country=CN") } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    // 1.4: the app pages are built with miuix (HyperOS-style Compose UI): ui, preference rows, backdrop blur for glass.
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")
    // 1.4: real page stack (own large title per page, swipe and predictive back), as miuix apps navigate.
    implementation("top.yukonga.miuix.kmp:miuix-nav:0.9.4")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    testImplementation("org.robolectric:robolectric:4.17")
}
