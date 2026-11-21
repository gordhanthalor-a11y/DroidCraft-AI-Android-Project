plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val signingStorePath = System.getenv("DROIDCRAFT_SIGNING_KEYSTORE_PATH").orEmpty()
val signingPassword = System.getenv("DROIDCRAFT_SIGNING_PASSWORD").orEmpty()
if (signingStorePath.isBlank() != signingPassword.isBlank()) {
    throw GradleException("DroidCraft debug signing requires both keystore path and password environment variables.")
}
val droidcraftDebugSigning = if (signingStorePath.isBlank()) null else signingStorePath to signingPassword

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

    // CI supplies the long-lived DroidCraft debug certificate through ephemeral environment variables.
    // Local builds without them use AGP's generic debug key and must not be installed as updates or shipped.
    if (droidcraftDebugSigning != null) {
        signingConfigs {
            create("droidcraftDebug") {
                storeFile = file(droidcraftDebugSigning.first)
                storePassword = droidcraftDebugSigning.second
                keyAlias = "droidcraft"
                keyPassword = droidcraftDebugSigning.second
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (droidcraftDebugSigning != null) {
                signingConfig = signingConfigs.getByName("droidcraftDebug")
            }
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
    implementation("androidx.compose.animation:animation")
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Android Room SQLite
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Gemini network clients
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // EncryptedSharedPreferences for provider API keys
    implementation(libs.androidx.security.crypto)

    // Optional, offline Hindi + Indian English wake phrase
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")

}