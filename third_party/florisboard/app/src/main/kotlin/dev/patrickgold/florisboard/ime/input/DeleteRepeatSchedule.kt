/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.input

/** Provides a gentle, bounded acceleration for held delete keys. */
internal object DeleteRepeatSchedule {
    private const val MIN_INTERVAL_MS = 24L
    private const val ACCELERATION_DURATION_MS = 1_200L

    fun interval(baseIntervalMs: Long, elapsedMs: Long): Long {
        val base = baseIntervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        val progress = elapsedMs.coerceAtLeast(0L).toDouble() / ACCELERATION_DURATION_MS
        val boundedProgress = progress.coerceIn(0.0, 1.0)
        return (base - ((base - MIN_INTERVAL_MS) * boundedProgress)).toLong()
            .coerceAtLeast(MIN_INTERVAL_MS)
    }
}
