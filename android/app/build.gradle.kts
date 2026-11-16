plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "pl.home.channeldeck"
    compileSdk = 34
    defaultConfig {
        applicationId = "pl.home.channeldeck"
        minSdk = 22
        targetSdk = 34
        versionCode = 13
        versionName = "0.9.1"
    }
    // Self-update only works when every build is signed with the same key. CI passes the shared key through IPTV_KEYSTORE
    // (the same file as ~/.android/debug.keystore on the build machine, so sticks installed from local builds keep updating).
    System.getenv("IPTV_KEYSTORE")?.takeIf { File(it).isFile }?.let { ks ->
        signingConfigs.getByName("debug") { storeFile = File(ks); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" }
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17"; freeCompilerArgs += "-opt-in=androidx.media3.common.util.UnstableApi" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("com.googlecode.soundlibs:jlayer:1.0.1.4")   // MP2 audio (LGPL)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
