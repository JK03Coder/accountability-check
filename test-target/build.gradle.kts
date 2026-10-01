plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nz.co.accountabilitycheck.testtarget"
    compileSdk = 36

    defaultConfig {
        applicationId = "nz.co.accountabilitycheck.testtarget"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// This package is only a development fixture, never a distributable product.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}
