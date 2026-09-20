plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.angelguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.angelguard"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        manifestPlaceholders["MAPS_API_KEY"] =
            project.findProperty("GOOGLE_MAPS_API_KEY") ?: ""
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")

    // Firebase
    implementation("com.google.firebase:firebase-storage:21.0.0")
    implementation("com.google.firebase:firebase-database:21.0.0")

    // Country Code Picker
    implementation("com.hbb20:ccp:2.7.0")

    // Work Manager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Google Maps
    implementation("com.google.android.gms:play-services-maps:19.0.0")

    // Google Places
    implementation("com.google.android.libraries.places:places:3.5.0")

    // Google Location
    implementation("com.google.android.gms:play-services-location:21.3.0")
}