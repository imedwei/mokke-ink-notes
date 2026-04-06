package com.writer.recognition

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

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
