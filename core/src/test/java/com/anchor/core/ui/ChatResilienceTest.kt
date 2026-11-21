// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import com.anchor.core.provider.AllProvidersFailedException
import com.anchor.core.provider.Http429Exception
import com.anchor.core.provider.HttpStatusException
import com.anchor.core.provider.OfflineException
import com.anchor.core.provider.ProviderTimeoutException
import com.anchor.core.ui.ChatResilience.SmallTalkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatResilienceTest {

    @Test fun errors_areExplainedInPlainLanguage_neverAsRawExceptions() {
        val wrapped = AllProvidersFailedException(HttpStatusException(401, "{\"error\":\"invalid_api_key\"}"))
        val f = ChatResilience.describe(wrapped)
        assertEquals("Your API key was rejected. Check it in Settings → AI provider.", f.message)
        assertFalse(f.retryable)
        assertTrue(f.needsSettings)

        assertTrue(ChatResilience.describe(Http429Exception(30)).message.contains("30 s"))
        assertTrue(ChatResilience.describe(Http429Exception(30)).retryable)
        assertTrue(ChatResilience.describe(ProviderTimeoutException()).retryable)
        assertTrue(ChatResilience.describe(HttpStatusException(503, "busy")).retryable)
        assertTrue(ChatResilience.describe(OfflineException()).message.startsWith("You're offline"))
        assertTrue(ChatResilience.describe(HttpStatusException(404, "model not found")).needsSettings)
        assertTrue(ChatResilience.describe(java.net.UnknownHostException("api.x")).retryable)
        val generic = ChatResilience.describe(IllegalStateException("kotlin.NullPointerException at Foo.kt:12"))
        assertFalse(generic.message.contains("Exception"))
    }

    @Test fun contextOverflow_isDetected_fromCommonProviderMessages() {
        assertTrue(ChatResilience.isContextOverflow(HttpStatusException(400, "This model's maximum context length is 8192 tokens")))
        assertTrue(ChatResilience.isContextOverflow(AllProvidersFailedException(HttpStatusException(413, ""))))
        assertTrue(ChatResilience.isContextOverflow(HttpStatusException(400, "prompt is too long: 210000 tokens > 200000")))
        assertFalse(ChatResilience.isContextOverflow(HttpStatusException(400, "invalid model")))
        assertFalse(ChatResilience.isContextOverflow(HttpStatusException(500, "context length")))
    }

    @Test fun hugeMessages_keepHeadAndTail() {
        val text = "Q: " + "a".repeat(50_000) + " END?"
        val capped = ChatResilience.capForModel(text, 1_000)
        assertTrue(capped.length <= 1_000 + 40)
        assertTrue(capped.startsWith("Q: "))
        assertTrue(capped.endsWith("END?"))
        assertTrue(capped.contains("characters omitted"))
        assertEquals("short", ChatResilience.capForModel("short", 1_000))
    }

    @Test fun smallTalk_isRecognised_inEnglishAndHinglish() {
        assertEquals(SmallTalkKind.GREETING, ChatResilience.classifySmallTalk("hiii"))
        assertEquals(SmallTalkKind.GREETING, ChatResilience.classifySmallTalk("Hello!"))
        assertEquals(SmallTalkKind.GREETING, ChatResilience.classifySmallTalk("namaste"))
        assertEquals(SmallTalkKind.HOW_ARE_YOU, ChatResilience.classifySmallTalk("hey how are you?"))
        assertEquals(SmallTalkKind.HOW_ARE_YOU, ChatResilience.classifySmallTalk("kaise ho"))
        assertEquals(SmallTalkKind.THANKS, ChatResilience.classifySmallTalk("thanks a lot!"))
        assertEquals(SmallTalkKind.THANKS, ChatResilience.classifySmallTalk("shukriya"))
        assertEquals(SmallTalkKind.MORNING, ChatResilience.classifySmallTalk("good morning"))
        assertEquals(SmallTalkKind.NIGHT, ChatResilience.classifySmallTalk("gn"))
        assertEquals(SmallTalkKind.BYE, ChatResilience.classifySmallTalk("ttyl"))
        assertEquals(SmallTalkKind.ACK, ChatResilience.classifySmallTalk("theek hai"))
        assertEquals(SmallTalkKind.WHO_ARE_YOU, ChatResilience.classifySmallTalk("what can you do?"))
    }

    @Test fun tasksAndRealQuestions_areNeverSmallTalk() {
        assertNull(ChatResilience.classifySmallTalk("hey remind me to call mom at 6"))
        assertNull(ChatResilience.classifySmallTalk("hi can you move dinner to tomorrow"))
        assertNull(ChatResilience.classifySmallTalk("ok add milk to my list"))
        assertNull(ChatResilience.classifySmallTalk("what is the capital of peru and why is it there historically"))
        assertNull(ChatResilience.classifySmallTalk(""))
        assertNull(ChatResilience.classifySmallTalk("history of the thanksgiving holiday"))
    }

    @Test fun localReplies_useTheName_andExplainWhatWorksWhenDisconnected() {
        val hi = ChatResilience.smallTalkReply(SmallTalkKind.GREETING, "Aarav", connected = true, hourOfDay = 10)
        assertEquals("Hey Aarav! I'm here.", hi)
        val offline = ChatResilience.smallTalkReply(SmallTalkKind.GREETING, null, connected = false, hourOfDay = 10)
        assertTrue(offline.startsWith("Hey! I'm here."))
        assertTrue(offline.contains("AI provider"))
        assertEquals("Anytime.", ChatResilience.smallTalkReply(SmallTalkKind.THANKS, "A", connected = false, hourOfDay = 10))
        val lateMorning = ChatResilience.smallTalkReply(SmallTalkKind.MORNING, null, connected = true, hourOfDay = 15)
        assertTrue(lateMorning.contains("afternoon"))
    }

    @Test fun progressLines_areNotAnswers() {
        assertTrue(ChatResilience.isProgressOnly(""))
        assertTrue(ChatResilience.isProgressOnly("Searching step 3..."))
        assertTrue(ChatResilience.isProgressOnly("Let me check that…"))
        assertFalse(ChatResilience.isProgressOnly("Paris is the capital of France."))
        assertFalse(ChatResilience.isProgressOnly("I'll be honest: I think you should take the job, because the growth matters more."))
    }
}
