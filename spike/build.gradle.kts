// Throwaway Phase 0 feasibility spikes A, B and C. Delete this module once the spike report is signed off.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.airtouch.spike"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.airtouch.spike"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "spike"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            assets.directories.add("../app/src/main/assets")
        }
    }

    androidResources {
        noCompress += "task"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mediapipe.tasks.vision)
}
