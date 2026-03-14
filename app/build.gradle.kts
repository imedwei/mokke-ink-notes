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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
            all {
                it.jvmArgs(
                    "--add-opens", "java.base/jdk.internal.access=ALL-UNNAMED",
                )
            }
        }
    }

    buildFeatures {
        viewBinding = true
        aidl = true
    }

    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

tasks.register("captureFixture") {
    description = "Capture handwriting fixture from device: -PfixtureName=hello -PexpectedText=\"hello\""
    dependsOn("installDebug", "installDebugAndroidTest")
    doLast {
        val name = project.property("fixtureName") as String
        val text = project.property("expectedText") as String
        val lang = project.findProperty("language") as? String ?: "en-US"
        val line = project.findProperty("lineIndex") as? String ?: "0"
        val adb = android.adbExecutable.absolutePath
        val appId = "com.writer.dev"

        exec {
            commandLine(adb, "shell", "am", "instrument", "-w",
                "-e", "class", "com.writer.recognition.StrokeFixtureCapture",
                "-e", "fixtureName", name,
                "-e", "expectedText", text,
                "-e", "language", lang,
                "-e", "lineIndex", line,
                "$appId.test/androidx.test.runner.AndroidJUnitRunner")
        }

        exec {
            commandLine(adb, "pull",
                "/sdcard/Download/inkup-fixtures/$name.json",
                "app/src/androidTest/assets/fixtures/$name.json")
        }

        exec {
            commandLine(adb, "shell", "rm",
                "/sdcard/Download/inkup-fixtures/$name.json")
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    testImplementation("org.robolectric:robolectric:4.14.1")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
