package com.writer.recognition

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Documents that ML Kit GenAI Speech Recognition is not available for
 * third-party apps as of 2026-04-05. Tested on:
 * - Boox Palma 2 Pro (AICore not installable from Play Store)
 * - Pixel 7 Pro (AICore present but API rejects non-Google-signed apps)
 *
 * Error: "PERMISSION_DENIED: Rejected by (1st-party only Allowlist)
 * security policy. Not google-signed."
 *
 * The API is at 1.0.0-alpha1 and restricted to Google's own apps.
 * Re-test when it reaches beta/stable.
 */
@RunWith(AndroidJUnit4::class)
class MlKitSpeechAvailabilityTest {

    private val tag = "MlKitSpeechCheck"

    @Test
    fun checkSpeechRecognitionAvailability() {
        runBlocking {
            try {
                val options = com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions.builder().build()
                val recognizer = com.google.mlkit.genai.speechrecognition.SpeechRecognition.getClient(options)
                Log.i(tag, "=== SpeechRecognition client created ===")

                val status = recognizer.checkStatus()
                Log.i(tag, "=== ML Kit Speech Status: $status ===")
            } catch (e: Exception) {
                Log.e(tag, "=== ML Kit Speech FAILED: ${e.javaClass.simpleName}: ${e.message} ===")
            }
        }
    }
}
