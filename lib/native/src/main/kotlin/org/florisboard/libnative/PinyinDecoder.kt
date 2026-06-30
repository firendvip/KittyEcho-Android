/*
 * Copyright (C) 2025 The WordTaker Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * JNI declarations for the bundled AOSP Google PinyinIME decoder.
 * Backed by libpinyinime.so (see src/main/cpp/pinyinime).
 *
 * The native decoder is a single global instance and is NOT thread-safe.
 * Callers must ensure all calls are serialized (the native layer also locks,
 * but logical sequences such as search -> getCandidate must not interleave).
 */

package org.florisboard.libnative

/** Opens the system dictionary from an already-open file descriptor + range. */
external fun nativeOpenDecoderFd(fd: Int, startOffset: Long, length: Long): Boolean

/** Releases the decoder and any loaded dictionary. */
external fun nativeCloseDecoder()

/** Runs a fresh search for the given pinyin bytes (ASCII a-z'). Returns candidate count. */
external fun nativeSearch(pyBuf: ByteArray, pyLen: Int): Int

/** Clears the current search session. */
external fun nativeResetSearch()

/** Returns candidate [candId] (0-based) as a Hanzi string, or "" if none. */
external fun nativeGetCandidate(candId: Int): String

/** Confirms candidate [candId]; returns remaining candidate count for the rest of the input. */
external fun nativeChoose(candId: Int): Int

/** Number of pinyin spellings already fixed/consumed by chosen candidates. */
external fun nativeGetFixedLen(): Int

/** Returns the decoded/segmented pinyin string for display above candidates. */
external fun nativeGetPyStr(): String
