package com.writer.recognition

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import kotlinx.coroutines.tasks.await

class ModelManager {

    companion object {
        private const val TAG = "ModelManager"
    }

    private val remoteModelManager = RemoteModelManager.getInstance()

    suspend fun ensureModelDownloaded(languageTag: String): DigitalInkRecognitionModel {
        val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)
            ?: throw IllegalArgumentException("No model for language: $languageTag")

        val model = DigitalInkRecognitionModel.builder(modelIdentifier).build()

        val isDownloaded = remoteModelManager.isModelDownloaded(model).await()
        if (!isDownloaded) {
            Log.i(TAG, "Downloading model for $languageTag...")
            val conditions = DownloadConditions.Builder().build()
            remoteModelManager.download(model, conditions).await()
            Log.i(TAG, "Model downloaded for $languageTag")
        } else {
            Log.i(TAG, "Model already available for $languageTag")
        }

        return model
    }
}
