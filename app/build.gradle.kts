plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val fixedSigningStoreFile = System.getenv("ANDROID_SIGNING_KEYSTORE_FILE")
val fixedSigningStorePassword = System.getenv("ANDROID_SIGNING_STORE_PASSWORD")
val fixedSigningKeyAlias = System.getenv("ANDROID_SIGNING_KEY_ALIAS")
val fixedSigningKeyPassword = System.getenv("ANDROID_SIGNING_KEY_PASSWORD")
val fixedSigningConfigured = listOf(
    fixedSigningStoreFile,
    fixedSigningStorePassword,
    fixedSigningKeyAlias,
    fixedSigningKeyPassword
).all { !it.isNullOrBlank() }

if (System.getenv("CI") == "true" && !fixedSigningConfigured) {
    throw GradleException(
        "CI builds must use the fixed signing keystore. Configure the ANDROID_SIGNING_* GitHub Actions secrets."
    )
}

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
        if (fixedSigningConfigured) {
            create("fixed") {
                storeFile = file(requireNotNull(fixedSigningStoreFile))
                storePassword = requireNotNull(fixedSigningStorePassword)
                keyAlias = requireNotNull(fixedSigningKeyAlias)
                keyPassword = requireNotNull(fixedSigningKeyPassword)
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (fixedSigningConfigured) {
                signingConfig = signingConfigs.getByName("fixed")
            }
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (fixedSigningConfigured) {
                signingConfig = signingConfigs.getByName("fixed")
            }
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
}