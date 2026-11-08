// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.smoke

/**
 * Marks a test that needs a real provider over the network.
 *
 * `app/build.gradle.kts` adds `notAnnotation=com.anchor.mine.smoke.LiveTest` to the runner
 * arguments unless Gradle is invoked with `-PliveTests`. Without that split the default
 * `connectedDebugAndroidTest` task can never be green without internet, and a network blip
 * reads as a product failure.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class LiveTest
