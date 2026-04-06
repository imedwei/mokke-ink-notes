#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// Package: com.writer.recognition.WhisperLib.Companion

JNIEXPORT jlong JNICALL
Java_com_writer_recognition_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    (void)thiz;
    const char *model_path = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    LOGI("Loading model from %s", model_path);
    struct whisper_context *ctx = whisper_init_from_file_with_params(
        model_path, whisper_context_default_params());
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path);
    if (ctx == NULL) {
        LOGI("Failed to load model");
    } else {
        LOGI("Model loaded successfully");
    }
    return (jlong)ctx;
}

JNIEXPORT void JNICALL
Java_com_writer_recognition_WhisperLib_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    (void)env; (void)thiz;
    whisper_free((struct whisper_context *)context_ptr);
}

JNIEXPORT void JNICALL
Java_com_writer_recognition_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jfloatArray audio_data, jstring vad_model_path_str) {
    (void)thiz;
    struct whisper_context *ctx = (struct whisper_context *)context_ptr;
    jfloat *data = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    jsize length = (*env)->GetArrayLength(env, audio_data);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = "en";
    params.n_threads = num_threads;
    params.no_context = true;
    params.single_segment = false;
    params.greedy.best_of = 1;        // Single greedy pass (default 5)
    params.temperature_inc = 0.0f;    // No temperature fallback retries
    params.no_timestamps = true;      // Skip timestamp token prediction

    // Enable VAD if model path is provided
    const char *vad_path = NULL;
    if (vad_model_path_str != NULL) {
        vad_path = (*env)->GetStringUTFChars(env, vad_model_path_str, NULL);
        if (vad_path != NULL && vad_path[0] != '\0') {
            params.vad = true;
            params.vad_model_path = vad_path;
            LOGI("VAD enabled: %s", vad_path);
        }
    }

    LOGI("Transcribing %d samples with %d threads (VAD=%s)", length, num_threads, params.vad ? "on" : "off");
    if (whisper_full(ctx, params, data, length) != 0) {
        LOGI("Transcription failed");
    }

    if (vad_path != NULL) {
        (*env)->ReleaseStringUTFChars(env, vad_model_path_str, vad_path);
    }
    (*env)->ReleaseFloatArrayElements(env, audio_data, data, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_com_writer_recognition_WhisperLib_00024Companion_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    (void)env; (void)thiz;
    return whisper_full_n_segments((struct whisper_context *)context_ptr);
}

JNIEXPORT jstring JNICALL
Java_com_writer_recognition_WhisperLib_00024Companion_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    (void)thiz;
    const char *text = whisper_full_get_segment_text(
        (struct whisper_context *)context_ptr, index);
    return (*env)->NewStringUTF(env, text);
}

JNIEXPORT jlong JNICALL
Java_com_writer_recognition_WhisperLib_00024Companion_getTextSegmentT0(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    (void)env; (void)thiz;
    return whisper_full_get_segment_t0(
        (struct whisper_context *)context_ptr, index);
}

JNIEXPORT jlong JNICALL
Java_com_writer_recognition_WhisperLib_00024Companion_getTextSegmentT1(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    (void)env; (void)thiz;
    return whisper_full_get_segment_t1(
        (struct whisper_context *)context_ptr, index);
}
