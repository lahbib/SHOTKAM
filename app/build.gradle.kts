plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kwaris.shootcam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kwaris.shootcam"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    signingConfigs {
        // Fixed key so every CI build can update the installed app (sideloaded, not Play Store).
        create("sideload") {
            storeFile = file("shootcam-release.jks")
            storePassword = System.getenv("SHOOTCAM_STORE_PASSWORD") ?: "shootcam-local"
            keyAlias = "shootcam"
            keyPassword = System.getenv("SHOOTCAM_KEY_PASSWORD") ?: "shootcam-local"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
