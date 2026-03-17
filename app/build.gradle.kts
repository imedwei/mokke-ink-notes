plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.writer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.writer"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "InkUp Dev")
        }
        release {
            isMinifyEnabled = false
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
        viewBinding = true
    }

    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

configurations.all {
    // Onyx SDK pulls in old pre-AndroidX support libraries that clash with AndroidX
    exclude(group = "com.android.support", module = "support-compat")
    exclude(group = "com.android.support", module = "support-annotations")
    exclude(group = "com.android.support", module = "support-v4")
}

dependencies {
    // Onyx Pen SDK (Boox stylus input)
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.2")
    implementation("com.onyx.android.sdk:onyxsdk-device:1.3.3")

    // Bypass hidden API restrictions (needed for Onyx SDK on Android 14+)
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")

    // Google ML Kit Digital Ink Recognition
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")

    // Room (persistence)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // AndroidX
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
}
