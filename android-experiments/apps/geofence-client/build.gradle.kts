plugins {
    id("com.android.application") version "8.5.2"
    kotlin("android") version "2.0.21"
}

android {
    namespace = "shield.loc.geofence"
    compileSdk = 34

    defaultConfig {
        applicationId = "shield.loc.geofence"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
