plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.kapt")
}

val appName = "Mokke - Ink Notes"

android {
    namespace = "com.writer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.writer"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["notAnnotation"] = "com.writer.recognition.DevTool"
        // Separate test APK package so connected tests don't replace the dev app
        testApplicationId = "com.writer.test"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "$appName Dev")
            ndk {
                abiFilters += "arm64-v8a"
            }
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
                // Pass golden file generation properties to test JVM
                val goldenVersion = project.findProperty("goldenVersion") as? String
                val goldenOutputDir = project.file("src/test/resources/golden").absolutePath
                if (goldenVersion != null) {
                    it.systemProperty("goldenVersion", goldenVersion)
                    it.systemProperty("goldenOutputDir", goldenOutputDir)
                }
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


/** Resolve an executable from PATH (needed on Windows where ProcessBuilder doesn't search PATH). */
fun resolveFromPath(name: String): String {
    val extensions = if (System.getProperty("os.name").lowercase().contains("win")) listOf(".exe", ".cmd", ".bat", "") else listOf("")
    return System.getenv("PATH").split(File.pathSeparator)
        .flatMap { dir -> extensions.map { ext -> File(dir, "$name$ext") } }
        .firstOrNull { it.exists() }
        ?.absolutePath ?: name
}

fun runScript(script: String, vararg args: String) {
    val proc = ProcessBuilder(resolveFromPath("bash"), script, *args)
        .directory(project.rootDir)
        .inheritIO()
        .start()
    val exitCode = proc.waitFor()
    if (exitCode != 0) {
        throw org.gradle.api.GradleException("Script failed with exit code $exitCode")
    }
}

tasks.register("localReview") {
    description = "Run local code review: ./gradlew localReview [-Pbase=master]"
    group = "verification"
    val base = providers.gradleProperty("base").getOrElse("master")
    val scriptPath = "${project.rootDir}/scripts/review-pr.sh"
    val rootDir = project.rootDir
    val bash = resolveFromPath("bash")
    doLast {
        val proc = ProcessBuilder(bash, scriptPath, "--local", "--no-post", "--base", base)
            .directory(rootDir)
            .inheritIO()
            .start()
        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            throw org.gradle.api.GradleException("Script failed with exit code $exitCode")
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
    // Proto schema (shared module — single source of truth)
    implementation(project(":proto"))

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

    // AppSearch (full-text search index)
    implementation("androidx.appsearch:appsearch:1.1.0-alpha05")
    implementation("androidx.appsearch:appsearch-local-storage:1.1.0-alpha05")
    // kapt required — no KSP support yet: https://issuetracker.google.com/issues/234116803
    kapt("androidx.appsearch:appsearch-compiler:1.1.0-alpha05")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")

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
    testImplementation("org.json:json:20231013")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

tasks.withType<Test> {
    testLogging {
        events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
        showStandardStreams = false
    }
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null) {
                println("${result.resultType}: ${result.successfulTestCount} passed, ${result.failedTestCount} failed, ${result.skippedTestCount} skipped")
            }
        }
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
    })
}

tasks.register("allTests") {
    description = "Runs all tests: unit tests and instrumented tests on connected device"
    group = "verification"
    dependsOn("testDebugUnitTest", "connectedDebugAndroidTest")
}
// Run unit tests first — fail fast before slower device tests
tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    mustRunAfter("testDebugUnitTest")
}
