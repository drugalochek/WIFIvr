plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.vroom.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.vroom.client"
        minSdk = 28
        targetSdk = 35
        versionCode = 20
        versionName = "0.4.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mediapipe:tasks-vision:0.10.21")
    implementation("com.google.ar:core:1.49.0")
}
