import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// local.properties nu este încărcat automat de Gradle — trebuie explicit
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "ro.pub.cs.system.eim.aplicatie_hack.wear"
    compileSdk = 36

    defaultConfig {
        // Același applicationId ca telefonul — necesar pentru Wearable Data Layer pairing
        applicationId = "ro.pub.cs.system.eim.aplicatie_hack"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        val mapsApiKey = localProperties.getProperty("MAPS_API_KEY") ?: ""
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
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
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    // Wear OS Compose — UI rotundă, optimizată pentru ceas
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)

    // Wearable Data Layer
    implementation(libs.play.services.wearable)

    // Locație (FusedLocationProviderClient — pentru Directions API origin)
    implementation(libs.play.services.location)

    // Coroutines
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
}