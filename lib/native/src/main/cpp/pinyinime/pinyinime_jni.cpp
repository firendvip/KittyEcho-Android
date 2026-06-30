/*
 * Copyright (C) 2025 The WordTaker Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Self-contained JNI bridge around the AOSP Google PinyinIME decoder
 * (ime_pinyin C API in include/pinyinime.h, Apache-2.0). Uses standard
 * Java_* JNI naming so no RegisterNatives is required.
 *
 * The underlying decoder keeps global mutable state and is NOT thread-safe,
 * so every entry point is serialized by a single mutex.
 */

#include <jni.h>
#include <pthread.h>
#include <string.h>

#include "include/pinyinime.h"

using namespace ime_pinyin;

namespace {
pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

struct ScopedLock {
  ScopedLock() { pthread_mutex_lock(&g_lock); }
  ~ScopedLock() { pthread_mutex_unlock(&g_lock); }
};

// utf16 string length helper (decoder returns NUL-terminated char16 buffers).
size_t u16len(const char16 *s) {
  size_t n = 0;
  while (s[n] != 0) n++;
  return n;
}
}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_florisboard_libnative_PinyinDecoderKt_nativeOpenDecoderFd(
    JNIEnv *env, jclass, jint fd, jlong startOffset, jlong length) {
  ScopedLock lock;
  // userdict path empty -> no user dictionary, purely read-only system dict.
  bool ok = im_open_decoder_fd(static_cast<int>(fd),
                               static_cast<long>(startOffset),
                               static_cast<long>(length), "");
  if (ok) {
    im_set_max_lens(32, 16);
  }
  return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_florisboard_libnative_PinyinDecoderKt_nativeCloseDecoder(JNIEnv *,
                                                                  jclass) {
  ScopedLock lock;
  im_close_decoder();
}

JNIEXPORT jint JNICALL
Java_org_florisboard_libnative_PinyinDecoderKt_nativeSearch(JNIEnv *env, jclass,
                                                            jbyteArray pyBuf,
                                                            jint pyLen) {
  ScopedLock lock;
  jint ret = 0;
  jbyte *body = env->GetByteArrayElements(pyBuf, nullptr);
  if (body != nullptr) {
    ret = static_cast<jint>(
        im_search(reinterpret_cast<const char *>(body),
                  static_cast<size_t>(pyLen)));
    env->ReleaseByteArrayElements(pyBuf, body, JNI_ABORT);
  }
  return ret;
}

JNIEXPORT void JNICALL
Java_org_florisboard_libnative_PinyinDecoderKt_nativeResetSearch(JNIEnv *,
                                                                 jclass) {
  ScopedLock lock;
  im_reset_search();
}

JNIEXPORT jstring JNICALL
Java_org_florisboard_libnative_PinyinDecoderKt_nativeGetCandidate(
    JNIEnv *env, jclass, jint candId) {
  ScopedLock lock;
  char16 buf[512];
  buf[0] = 0;
  char16 *res = im_get_candidate(static_cast<size_t>(candId), buf, 512);
  if (res == nullptr) {
    return env->NewString(reinterpret_cast<const jchar *>(buf), 0);
  }
  return env->NewString(reinterpret_cast<const jchar *>(buf),
                        static_cast<jsize>(u16len(buf)));
}

JNIEXPORT jint JNICALL
Java_org_florisboard_libnative_PinyinDecoderKt_nativeChoose(JNIEnv *, jclass,
                                                            jint candId) {
  ScopedLock lock;
  return static_cast<jint>(im_choose(static_cast<size_t>(candId)));
}

JNIEXPORT jint JNICALL
Java_org_florisboard_libnative_PinyinDecoderKt_nativeGetFixedLen(JNIEnv *,
                                                                 jclass) {
  ScopedLock lock;
  return static_cast<jint>(im_get_fixed_len());
}

JNIEXPORT jstring JNICALL
Java_org_florisboard_libnative_PinyinDecoderKt_nativeGetPyStr(JNIEnv *env,
                                                              jclass) {
  ScopedLock lock;
  size_t pyLen = 0;
  const char *py = im_get_sps_str(&pyLen);
  if (py == nullptr) {
    return env->NewStringUTF("");
  }
  // py is a NUL-terminated ASCII (spelling) string; pyLen is the decoded
  // length. Build a fresh NUL-terminated copy of the decoded prefix.
  char tmp[256];
  size_t n = pyLen < sizeof(tmp) - 1 ? pyLen : sizeof(tmp) - 1;
  memcpy(tmp, py, n);
  tmp[n] = '\0';
  return env->NewStringUTF(tmp);
}

}  // extern "C"
