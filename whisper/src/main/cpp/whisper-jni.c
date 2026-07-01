#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"

#define TAG "whisper-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

JNIEXPORT jlong JNICALL
Java_com_example_whisper_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    (void) thiz;
    const char *model_path = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    LOGI("Loading whisper model from %s", model_path);
    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context *context =
            whisper_init_from_file_with_params(model_path, cparams);
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path);
    if (context == NULL) {
        LOGW("Failed to load whisper model");
    }
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_example_whisper_WhisperLib_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    (void) env; (void) thiz;
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    if (context != NULL) whisper_free(context);
}

// Transcribe float PCM (16 kHz, mono) and return the concatenated text.
JNIEXPORT jstring JNICALL
Java_com_example_whisper_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads,
        jfloatArray audio_data, jstring language_str) {
    (void) thiz;
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    if (context == NULL) return (*env)->NewStringUTF(env, "");

    jfloat *audio = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize n_samples = (*env)->GetArrayLength(env, audio_data);

    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.no_context = true;
    params.single_segment = false;
    params.n_threads = num_threads;

    const char *language = NULL;
    if (language_str != NULL) {
        language = (*env)->GetStringUTFChars(env, language_str, NULL);
    }
    // NULL or "auto" lets whisper auto-detect the language.
    params.language = (language != NULL && strcmp(language, "auto") != 0) ? language : NULL;
    params.detect_language = (params.language == NULL);

    jstring result;
    if (whisper_full(context, params, audio, n_samples) != 0) {
        LOGW("whisper_full failed");
        result = (*env)->NewStringUTF(env, "");
    } else {
        const int n_segments = whisper_full_n_segments(context);
        size_t cap = 256;
        size_t len = 0;
        char *buf = (char *) malloc(cap);
        buf[0] = '\0';
        for (int i = 0; i < n_segments; i++) {
            const char *seg = whisper_full_get_segment_text(context, i);
            size_t seg_len = strlen(seg);
            if (len + seg_len + 1 > cap) {
                while (len + seg_len + 1 > cap) cap *= 2;
                buf = (char *) realloc(buf, cap);
            }
            memcpy(buf + len, seg, seg_len);
            len += seg_len;
            buf[len] = '\0';
        }
        result = (*env)->NewStringUTF(env, buf);
        free(buf);
    }

    if (language != NULL) {
        (*env)->ReleaseStringUTFChars(env, language_str, language);
    }
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio, JNI_ABORT);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_example_whisper_WhisperLib_00024Companion_getSystemInfo(
        JNIEnv *env, jobject thiz) {
    (void) thiz;
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}
