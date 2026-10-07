/*
 * JNI bridge for com.paperboy.android.NativeCore.
 * Not thread-safe: the Kotlin side calls everything from one emulation thread.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "gbemu.h"

#define JNI_FN(name) Java_com_paperboy_android_NativeCore_##name

static jbyteArray make_byte_array(JNIEnv *env, const uint8_t *data, size_t size)
{
    jbyteArray arr = (*env)->NewByteArray(env, (jsize)size);
    if (arr == NULL) {
        return NULL; /* OutOfMemoryError already pending */
    }
    (*env)->SetByteArrayRegion(env, arr, 0, (jsize)size, (const jbyte *)data);
    return arr;
}

/* Copies a Java byte[] into a malloc'd buffer (caller frees). */
static uint8_t *copy_byte_array(JNIEnv *env, jbyteArray arr, size_t *out_size)
{
    jsize len;
    uint8_t *buf;

    if (arr == NULL) {
        return NULL;
    }
    len = (*env)->GetArrayLength(env, arr);
    if (len <= 0) {
        return NULL;
    }
    buf = (uint8_t *)malloc((size_t)len);
    if (buf == NULL) {
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, arr, 0, len, (jbyte *)buf);
    *out_size = (size_t)len;
    return buf;
}

JNIEXPORT jboolean JNICALL JNI_FN(nInit)(JNIEnv *env, jobject thiz, jbyteArray rom)
{
    size_t size = 0;
    uint8_t *buf = copy_byte_array(env, rom, &size);
    jboolean ok;
    (void)thiz;
    if (buf == NULL) {
        return JNI_FALSE;
    }
    ok = paperboy_gb_init(buf, size) ? JNI_TRUE : JNI_FALSE;
    free(buf); /* the core made its own copy */
    return ok;
}

JNIEXPORT void JNICALL JNI_FN(nShutdown)(JNIEnv *env, jobject thiz)
{
    (void)env; (void)thiz;
    paperboy_gb_shutdown();
}

JNIEXPORT jobject JNICALL JNI_FN(nVideoBuffer)(JNIEnv *env, jobject thiz)
{
    (void)thiz;
    return (*env)->NewDirectByteBuffer(env, paperboy_gb_video_buffer(), (jlong)paperboy_gb_video_buffer_bytes());
}

JNIEXPORT jobject JNICALL JNI_FN(nAudioBuffer)(JNIEnv *env, jobject thiz)
{
    (void)thiz;
    return (*env)->NewDirectByteBuffer(env, paperboy_gb_audio_buffer(), (jlong)paperboy_gb_audio_buffer_bytes());
}

JNIEXPORT void JNICALL JNI_FN(nSetPalette)(JNIEnv *env, jobject thiz, jintArray argb)
{
    jint c[4];
    uint32_t rgba[4];
    (void)thiz;
    if (argb == NULL || (*env)->GetArrayLength(env, argb) < 4) {
        return;
    }
    (*env)->GetIntArrayRegion(env, argb, 0, 4, c);
    for (int i = 0; i < 4; i++) {
        /* Android colour int is 0xAARRGGBB; the bitmap wants bytes R,G,B,A. */
        const uint32_t v = (uint32_t)c[i];
        rgba[i] = ((v >> 16) & 0xFFu) | (v & 0xFF00u) | ((v & 0xFFu) << 16) | 0xFF000000u;
    }
    paperboy_gb_set_palette(rgba);
}

JNIEXPORT void JNICALL JNI_FN(nSetButtons)(JNIEnv *env, jobject thiz, jint mask)
{
    (void)env; (void)thiz;
    paperboy_gb_set_buttons((uint8_t)(mask & 0xFF));
}

JNIEXPORT jboolean JNICALL JNI_FN(nRunFrame)(JNIEnv *env, jobject thiz, jboolean render, jboolean audio)
{
    (void)env; (void)thiz;
    return paperboy_gb_run_frame(render != JNI_FALSE, audio != JNI_FALSE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nFrameUpdated)(JNIEnv *env, jobject thiz)
{
    (void)env; (void)thiz;
    return paperboy_gb_frame_updated() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nForceRedraw)(JNIEnv *env, jobject thiz)
{
    (void)env; (void)thiz;
    paperboy_gb_force_redraw();
}

JNIEXPORT jboolean JNICALL JNI_FN(nHasPersist)(JNIEnv *env, jobject thiz)
{
    (void)env; (void)thiz;
    return paperboy_gb_has_persist() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nPersistDirty)(JNIEnv *env, jobject thiz)
{
    (void)env; (void)thiz;
    return paperboy_gb_persist_is_dirty() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nPersistExport)(JNIEnv *env, jobject thiz, jint unixSeconds)
{
    const size_t size = paperboy_gb_persist_size();
    uint8_t *buf;
    jbyteArray result = NULL;
    (void)thiz;
    if (size == 0 || (buf = (uint8_t *)malloc(size)) == NULL) {
        return NULL;
    }
    if (paperboy_gb_persist_export(buf, size, (uint32_t)unixSeconds)) {
        result = make_byte_array(env, buf, size);
    }
    free(buf);
    return result;
}

JNIEXPORT jboolean JNICALL JNI_FN(nPersistImport)(JNIEnv *env, jobject thiz, jbyteArray data, jint unixSeconds)
{
    size_t size = 0;
    uint8_t *buf = copy_byte_array(env, data, &size);
    jboolean ok;
    (void)thiz;
    if (buf == NULL) {
        return JNI_FALSE;
    }
    ok = paperboy_gb_persist_import(buf, size, (uint32_t)unixSeconds) ? JNI_TRUE : JNI_FALSE;
    free(buf);
    return ok;
}

JNIEXPORT void JNICALL JNI_FN(nPersistMarkClean)(JNIEnv *env, jobject thiz)
{
    (void)env; (void)thiz;
    paperboy_gb_persist_mark_clean();
}

JNIEXPORT jbyteArray JNICALL JNI_FN(nStateExport)(JNIEnv *env, jobject thiz)
{
    const size_t size = paperboy_gb_state_size();
    uint8_t *buf;
    jbyteArray result = NULL;
    (void)thiz;
    if (size == 0 || (buf = (uint8_t *)malloc(size)) == NULL) {
        return NULL;
    }
    if (paperboy_gb_state_export(buf, size)) {
        result = make_byte_array(env, buf, size);
    }
    free(buf);
    return result;
}

JNIEXPORT jboolean JNICALL JNI_FN(nStateImport)(JNIEnv *env, jobject thiz, jbyteArray data)
{
    size_t size = 0;
    uint8_t *buf = copy_byte_array(env, data, &size);
    jboolean ok;
    (void)thiz;
    if (buf == NULL) {
        return JNI_FALSE;
    }
    ok = paperboy_gb_state_import(buf, size) ? JNI_TRUE : JNI_FALSE;
    free(buf);
    return ok;
}

JNIEXPORT jstring JNICALL JNI_FN(nLastError)(JNIEnv *env, jobject thiz)
{
    (void)thiz;
    return (*env)->NewStringUTF(env, paperboy_gb_last_error());
}

JNIEXPORT jstring JNICALL JNI_FN(nTitle)(JNIEnv *env, jobject thiz)
{
    (void)thiz;
    return (*env)->NewStringUTF(env, paperboy_gb_title());
}
