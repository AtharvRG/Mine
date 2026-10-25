// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine Author
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTroubleTest {

    @Test
    fun testProviderTrouble_E06() {
        runBlocking {
            var simulatedTime = 1000L
            val limiter = RateLimiter { simulatedTime }

            val primary = Target("t_primary", "key_A", 20)
            val fallback = Target("t_fallback", "key_B", 20)

            var primaryCallCount = 0
            var fallbackCallCount = 0

            val transport = object : HttpTransport {
                override suspend fun postStream(target: Target, body: String): Flow<String> {
                    // Real HTTP clients read headers first. A 429 happens here, before the stream opens.
                    if (target.id == "t_primary") {
                        primaryCallCount++
                        throw Http429Exception(retryAfterSeconds = null)
                    } else {
                        fallbackCallCount++
                        return flow {
                            emit("data: {\"choices\":[{\"delta\":{\"content\":\"fallback_success\"}}]}")
                            emit("data: [DONE]")
                        }
                    }
                }
            }

            val orchestrator = ProviderOrchestrator(limiter, transport)
            val chain = listOf(primary, fallback)

            // 1. First call. Primary throws 429 before stream. Caught, cooldown applied, fallback hit.
            val results = orchestrator.chat(chain, "hello").toList()

            assertEquals(1, primaryCallCount)
            assertEquals(1, fallbackCallCount)
            assertEquals(2, results.size)
            assertTrue(results[0] is StreamEvent.TextDelta)
            assertEquals("fallback_success", (results[0] as StreamEvent.TextDelta).text)

            // 2. Verify Primary is in 15-min cooldown
            val expiry = limiter.getCooldownExpiry("key_A")
            assertNotNull("Primary key should be in cooldown", expiry)
            assertEquals(simulatedTime + (15 * 60 * 1000L), expiry)

            // 3. Second call. Primary should be skipped entirely by the limiter without network IO
            val results2 = orchestrator.chat(chain, "hello again").toList()

            assertEquals("Network call to primary should be bypassed", 1, primaryCallCount)
            assertEquals("Fallback should be hit again", 2, fallbackCallCount)
            assertEquals(2, results2.size)

            // 4. Advance time by 16 minutes. Primary should recover.
            simulatedTime += 16 * 60 * 1000L

            // If we called it again here, the limiter would allow it to pass through.
        }
    }
}