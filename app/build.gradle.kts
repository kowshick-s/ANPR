plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.vehiclegate.anpr"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vehiclegate.anpr"
        minSdk = 24
        targetSdk = 34
        // CI passes -PbuildNumber=<run number> so every build installs over the previous one
        val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // TV boxes / phones are ARM. Keeping only ARM keeps the APK small (LibVLC is native code).
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so a release APK can be side-loaded straight onto the TV box.
            // Replace with your own signingConfig for production.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    lint { abortOnError = false }
}

dependencies {
    val room = "2.6.1"

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // RTSP playback: LibVLC, the engine inside the VLC app (handles NVRs that omit "fmtp" in SDP,
    // passwords with special characters, H.265, large 4K/5MP frames …)
    implementation("org.videolan.android:libvlc-all:3.6.5")

    // On-device OCR, bundled model -> works on TV boxes without Google Play Services
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Entry log
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    testImplementation("junit:junit:4.13.2")
}
