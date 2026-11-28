plugins {
    id("com.android.application")
}

android {
    namespace = "com.jisudownloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jisudownloader"
        minSdk = 24
        targetSdk = 34
        versionCode = 31
        versionName = "1.31"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(files("libs/zxing-core-3.5.3.jar"))
}