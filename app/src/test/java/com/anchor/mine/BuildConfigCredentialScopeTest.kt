// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H1: live-provider credentials must be scoped to the debug variant.
 *
 * The defect this guards against: declaring `LIVE_*` in `defaultConfig` put the API key into
 * every variant's BuildConfig, including release. R8 stripped the unreferenced field from the
 * minified APK, but that was an accident of minification - a release-reachable reference to
 * `BuildConfig.LIVE_API_KEY` shipped the key in cleartext (proven by experiment: adding
 * `Log.e("H1X", BuildConfig.LIVE_API_KEY + BuildConfig.LIVE_ENDPOINT)` to `MineApp.onCreate`
 * put both the key and the endpoint into `classes.dex` of the release APK).
 *
 * This test runs under `testDebugUnitTest`, i.e. against the DEBUG variant, so it can only
 * assert the positive half: the fields exist and carry the configured values. The negative
 * half - that the release variant has no `LIVE_*` fields at all - is enforced at build time:
 * `app/build.gradle.kts` declares them inside `buildTypes { debug { ... } }` only, and the
 * release BuildConfig.java is generated without them. See docs/agent/work/h1-api-key-scope.md
 * for the recorded `javap` / dex-scan evidence.
 */
class BuildConfigCredentialScopeTest {

    @Test
    fun testDebugVariant_ExposesLiveCredentials() {
        // The debug variant is the one that runs LiveSmoke15Test, so it must still carry the
        // fields. If this fails, the live tests silently stop receiving credentials.
        assertEquals("debug", BuildConfig.BUILD_TYPE)
        assertTrue("LIVE_ENDPOINT must be a URL in the debug variant",
            BuildConfig.LIVE_ENDPOINT.startsWith("http"))
        assertTrue("LIVE_MODEL must be non-blank in the debug variant",
            BuildConfig.LIVE_MODEL.isNotBlank())
        // LIVE_API_KEY may legitimately be empty on a machine without local.properties; the
        // live test skips itself in that case. Assert only that the field exists and is a
        // String - a missing field would not compile at all, so this is the compile-time guard.
        assertTrue(BuildConfig.LIVE_API_KEY.length >= 0)
    }
}
