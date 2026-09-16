import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

android {
    // Principal Architect Note: Namespace must match your refactored package
    namespace = "com.example.smartroaddamage"
    compileSdk = 36

    val localProps = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localProps.load(FileInputStream(localPropertiesFile))
    }

    defaultConfig {
        // Critical: applicationId must match the Firebase Console registration
        applicationId = "com.example.smartroaddamage"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "MAPPLS_ATLAS_CLIENT_ID",
            localProps.getProperty("MAPPLS_ATLAS_CLIENT_ID")?.let { "\"$it\"" } ?: "\"\""
        )
        buildConfigField(
            "String",
            "MAPPLS_ATLAS_CLIENT_SECRET",
            localProps.getProperty("MAPPLS_ATLAS_CLIENT_SECRET")?.let { "\"$it\"" } ?: "\"\""
        )
        buildConfigField(
            "String",
            "MAPPLS_MAP_REST_API_KEY",
            localProps.getProperty("MAPPLS_MAP_REST_API_KEY")?.let { "\"$it\"" } ?: "\"\""
        )
        buildConfigField(
            "String",
            "MAPPLS_ATLAS_GRANT_TYPE",
            localProps.getProperty("MAPPLS_ATLAS_GRANT_TYPE")?.let { "\"$it\"" } ?: "\"client_credentials\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // --- 1. Core Android & Lifecycle ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // --- 2. Jetpack Compose Stack ---
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // --- 3. Firebase & Analytics (2026 BoM Managed) ---
    implementation(platform("com.google.firebase:firebase-bom:34.11.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-firestore")

    // --- 4. Modern Identity & Credential Manager ---
    // Using 1.5.0 and 1.2.0 as identified in your IDE suggestions
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // --- 5. External Integration & Storage ---
    implementation(libs.maps.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Note: Reverted to legacy file-less auth tracking via MapplsAccountManager.
    // Mappls SDK plugin is incompatible with AGP 8.0+.
    implementation("com.mappls.sdk:mappls-android-sdk:8.3.0")

    // --- 5b. HTTP client for Mappls Direction REST API ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // --- 6. PyTorch Mobile for Anomaly Detection ML Inference ---
    implementation("org.pytorch:pytorch_android_lite:1.13.1")
    implementation("org.pytorch:pytorch_android_torchvision_lite:1.13.1")

    // --- 6b. ONNX Runtime + CameraX for headless road-scene validation ---
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // --- 7. Testing Frameworks ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
