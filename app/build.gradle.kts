plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Fixed debug identity for personal sideloaded builds; keep this key stable across CI builds.
val droidcraftDebugSigningPassword = "DroidCraftDebugKey2026"
val droidcraftDebugSigningAlias = "droidcraft"

android {
    namespace = "com.droidcraft.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.droidcraft.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("droidcraftDebug") {
            storeFile = rootProject.file("keystore/droidcraft-debug.keystore")
            storePassword = droidcraftDebugSigningPassword
            keyAlias = droidcraftDebugSigningAlias
            keyPassword = droidcraftDebugSigningPassword
            storeType = "PKCS12"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("droidcraftDebug")
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Jetpack Compose & Material 3
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(libs.androidx.navigation.compose)

    // Android Room SQLite
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Android WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Retrofit & Networking
    implementation(libs.retrofit)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.retrofit.converter.gson)
    implementation(libs.coil.compose)

    // Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // EncryptedSharedPreferences for local API keys and OAuth credentials
    implementation(libs.androidx.security.crypto)

    // Offline, on-device wake-word detection
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
}