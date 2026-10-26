plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.oussama.island"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.oussama.island"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0 by oussama"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
