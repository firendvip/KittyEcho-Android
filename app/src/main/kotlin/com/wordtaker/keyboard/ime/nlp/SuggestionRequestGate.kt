/*
 * Copyright (C) 2026 The WordTaker Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wordtaker.keyboard.ime.nlp

/**
 * Serializes suggestion request creation with completion publication.
 *
 * Starting a request invalidates cloud work while holding the same monitor used
 * to check and publish completed local work. Therefore an older completion can
 * never pass its latest-ID check and then race a newer request's invalidation.
 */
internal class SuggestionRequestGate(
    private val invalidateCloud: () -> Unit,
) {
    private val monitor = Any()
    private var latestRequestId = 0L

    fun beginRequest(): Long = synchronized(monitor) {
        latestRequestId += 1
        invalidateCloud()
        latestRequestId
    }

    fun runIfLatest(requestId: Long, action: () -> Unit): Boolean = synchronized(monitor) {
        if (requestId != latestRequestId) {
            false
        } else {
            action()
            true
        }
    }

    fun <T> withLock(action: () -> T): T = synchronized(monitor) {
        action()
    }
}
