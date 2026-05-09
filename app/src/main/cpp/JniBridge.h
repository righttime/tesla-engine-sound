#ifndef JNI_BRIDGE_H
#define JNI_BRIDGE_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

// JNI methods - implemented in JniBridge.cpp

JNIEXPORT jlong JNICALL
Java_com_enginesim_app_EngineSimLibrary_nativeCreate(JNIEnv* env, jobject thiz);

JNIEXPORT void JNICALL
Java_com_enginesim_app_EngineSimLibrary_nativeDestroy(JNIEnv* env, jobject thiz, jlong handle);

JNIEXPORT jboolean JNICALL
Java_com_enginesim_app_EngineSimLibrary_nativeInitializeAudio(JNIEnv* env, jobject thiz,
    jlong handle, jint sampleRate, jint channelCount, jint bufferSizeFrames,
    jobject assetManager);

JNIEXPORT void JNICALL
Java_com_enginesim_app_EngineSimLibrary_nativeStart(JNIEnv* env, jobject thiz, jlong handle);

JNIEXPORT void JNICALL
Java_com_enginesim_app_EngineSimLibrary_nativeStop(JNIEnv* env, jobject thiz, jlong handle);

JNIEXPORT void JNICALL
Java_com_enginesim_app_EngineSimLibrary_nativeSetThrottle(JNIEnv* env, jobject thiz,
    jlong handle, jfloat throttle);

JNIEXPORT void JNICALL
Java_com_enginesim_app_EngineSimLibrary_nativeSetVolume(JNIEnv* env, jobject thiz,
    jlong handle, jfloat volume);

JNIEXPORT jfloat JNICALL
Java_com_enginesim_app_EngineSimLibrary_nativeGetRpm(JNIEnv* env, jobject thiz, jlong handle);

JNIEXPORT jfloat JNICALL
Java_com_enginesim_app_EngineSimLibrary_nativeGetTorque(JNIEnv* env, jobject thiz, jlong handle);

JNIEXPORT jfloat JNICALL
Java_com_enginesim_app_EngineSimLibrary_nativeGetPower(JNIEnv* env, jobject thiz, jlong handle);

JNIEXPORT jint JNICALL
Java_com_enginesim_app_EngineSimLibrary_nativeReadAudio(JNIEnv* env, jobject thiz,
    jlong handle, jshortArray buffer, jint samples);

JNIEXPORT jboolean JNICALL
Java_com_enginesim_app_EngineSimLibrary_nativeSimulateStep(JNIEnv* env, jobject thiz, jlong handle);

JNIEXPORT jboolean JNICALL
Java_com_enginesim_app_EngineSimLibrary_nativeLoadEnginePreset(JNIEnv* env, jobject thiz,
    jlong handle, jstring presetName, jint cylinderCount, jfloat boreMm,
    jfloat strokeMm, jfloat compressionRatio);

JNIEXPORT jboolean JNICALL
Java_com_enginesim_app_EngineSimLibrary_nativeLoadEnginePresetByName(JNIEnv* env, jobject thiz,
    jlong handle, jstring presetName);

#ifdef __cplusplus
}
#endif

#endif // JNI_BRIDGE_H