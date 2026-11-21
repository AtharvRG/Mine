// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationReadingTest {

    private val own = "com.anchor.mine"
    private var clock = 1_700_000_000_000L
    private val on = NotificationReadSettings(enabled = true)
    private fun ctx(
        ambient: Boolean = true,
        minute: Int = 14 * 60,
        inCall: Boolean = false,
        busy: Boolean = false,
        headphones: Boolean = false,
        hide: Boolean = false,
        dnd: Boolean = false,
    ) = ReaderContext(
        nowMs = clock, ambientActive = ambient, ownPackage = own, inCall = inCall,
        conversationBusy = busy, headphonesConnected = headphones, minuteOfDay = minute,
        dayStartMinute = 7 * 60, dayEndMinute = 22 * 60, hideContent = hide, dndActive = dnd,
    )

    private var seq = 0
    private fun wa(text: String, sender: String = "Riya", key: String = "wa|${seq++}") = IncomingNotification(
        key = key, packageName = "com.whatsapp", appLabel = "WhatsApp", title = sender, text = text,
        category = "msg", postedAt = clock, sender = sender,
    )

    // ---------- Triage: hard drops ----------

    @Test fun offFeature_isDropped() {
        val d = NotificationTriage().evaluate(wa("hi"), NotificationReadSettings(enabled = false), ctx())
        assertEquals(ReadDecision.Drop(SkipReason.DISABLED), d)
    }

    @Test fun ambientOff_isDropped_evenWhenEnabled() {
        val d = NotificationTriage().evaluate(wa("hi"), on, ctx(ambient = false))
        assertEquals(ReadDecision.Drop(SkipReason.AMBIENT_OFF), d)
    }

    @Test fun ownApp_ongoing_summary_andSystemCategories_areNeverRead() {
        val t = NotificationTriage()
        assertEquals(ReadDecision.Drop(SkipReason.OWN_APP), t.evaluate(wa("x").copy(packageName = own), on, ctx()))
        assertEquals(ReadDecision.Drop(SkipReason.ONGOING), t.evaluate(wa("x").copy(isOngoing = true), on, ctx()))
        assertEquals(ReadDecision.Drop(SkipReason.GROUP_SUMMARY), t.evaluate(wa("x").copy(isGroupSummary = true), on, ctx()))
        for (cat in listOf("call", "alarm", "progress", "transport", "navigation")) {
            assertEquals(cat, ReadDecision.Drop(SkipReason.CATEGORY), t.evaluate(wa("x $cat").copy(category = cat), on, ctx()))
        }
    }

    @Test fun messagesScope_skipsNonMessagingApps_unlessAllowed() {
        val shop = IncomingNotification("s|1", "com.shop", "Shop", "Order shipped", "Arrives Tuesday", category = null, postedAt = clock)
        assertEquals(ReadDecision.Drop(SkipReason.NOT_IN_SCOPE), NotificationTriage().evaluate(shop, on, ctx()))
        val allowed = on.copy(allowedPackages = setOf("com.shop"))
        assertTrue(NotificationTriage().evaluate(shop, allowed, ctx()) is ReadDecision.Speak)
    }

    @Test fun blockedApp_beatsAllowlist() {
        val s = on.copy(allowedPackages = setOf("com.whatsapp"), blockedPackages = setOf("com.whatsapp"))
        assertEquals(ReadDecision.Drop(SkipReason.BLOCKED_APP), NotificationTriage().evaluate(wa("hi"), s, ctx()))
    }

    @Test fun lowImportanceAndPromo_areDropped_unlessExplicitlyAllowed() {
        val t = NotificationTriage()
        assertEquals(ReadDecision.Drop(SkipReason.LOW_PRIORITY), t.evaluate(wa("quiet").copy(importance = 2), on, ctx()))
        assertEquals(ReadDecision.Drop(SkipReason.LOW_PRIORITY), t.evaluate(wa("sale!").copy(category = "promo"), on, ctx()))
        val allowed = on.copy(allowedPackages = setOf("com.whatsapp"))
        assertTrue(NotificationTriage().evaluate(wa("quiet").copy(importance = 2), allowed, ctx()) is ReadDecision.Speak)
    }

    @Test fun repostOfSameKeyAndText_isReadOnce_butNewTextOnSameKeyIsRead() {
        val t = NotificationTriage()
        assertTrue(t.evaluate(wa("on my way", key = "k"), on, ctx()) is ReadDecision.Speak)
        clock += 5_000
        assertEquals(ReadDecision.Drop(SkipReason.DUPLICATE), t.evaluate(wa("on my way", key = "k"), on, ctx()))
        clock += 5_000
        assertTrue(t.evaluate(wa("here now", key = "k"), on, ctx()) is ReadDecision.Speak)
    }

    @Test fun sameTextUnderTwoKeys_isReadOnce() {
        val t = NotificationTriage()
        assertTrue(t.evaluate(wa("dinner at 8?", key = "a"), on, ctx()) is ReadDecision.Speak)
        assertEquals(ReadDecision.Drop(SkipReason.DUPLICATE), t.evaluate(wa("dinner at 8?", key = "b"), on, ctx()))
    }

    // ---------- Triage: holds ----------

    @Test fun holds_keepTheNotification_butStaySilent() {
        assertEquals(ReadDecision.Hold(SkipReason.IN_CALL), NotificationTriage().evaluate(wa("a"), on, ctx(inCall = true)))
        assertEquals(ReadDecision.Hold(SkipReason.CONVERSATION), NotificationTriage().evaluate(wa("b"), on, ctx(busy = true)))
        assertEquals(ReadDecision.Hold(SkipReason.QUIET_HOURS), NotificationTriage().evaluate(wa("c"), on, ctx(minute = 23 * 60)))
        assertEquals(ReadDecision.Hold(SkipReason.NO_HEADPHONES), NotificationTriage().evaluate(wa("d"), on.copy(headphonesOnly = true), ctx()))
        assertEquals(ReadDecision.Hold(SkipReason.SPEECH_OFF), NotificationTriage().evaluate(wa("e"), on.copy(speak = false), ctx()))
        assertEquals(ReadDecision.Hold(SkipReason.DND), NotificationTriage().evaluate(wa("f"), on, ctx(dnd = true)))
        assertTrue(NotificationTriage().evaluate(wa("g").copy(passesDnd = true), on, ctx(dnd = true)) is ReadDecision.Speak)
    }

    @Test fun quietHours_canBeTurnedOff() {
        val d = NotificationTriage().evaluate(wa("late"), on.copy(respectQuietHours = false), ctx(minute = 23 * 60))
        assertTrue(d is ReadDecision.Speak)
    }

    @Test fun rateLimit_holdsTheBurst_thenCatchesUpInOneSentence() {
        val t = NotificationTriage()
        val s = on.copy(maxPerMinute = 2)
        assertTrue(t.evaluate(wa("1"), s, ctx()) is ReadDecision.Speak)
        assertTrue(t.evaluate(wa("2"), s, ctx()) is ReadDecision.Speak)
        assertEquals(ReadDecision.Hold(SkipReason.RATE_LIMITED), t.evaluate(wa("3"), s, ctx()))
        assertEquals(ReadDecision.Hold(SkipReason.RATE_LIMITED), t.evaluate(wa("4"), s, ctx()))
        assertEquals(ReadDecision.Hold(SkipReason.RATE_LIMITED), t.evaluate(wa("5", sender = "Arjun"), s, ctx()))
        assertNull("no catch-up while the minute is still full", t.takeCatchUp(s, ctx()))
        clock += 61_000
        val line = t.takeCatchUp(s, ctx())
        assertEquals("Also, 2 messages from Riya on WhatsApp and a message from Arjun on WhatsApp.", line)
        assertNull("caught up only once", t.takeCatchUp(s, ctx()))
    }

    @Test fun catchUp_isDiscarded_whenQuietHoursBegin() {
        val t = NotificationTriage()
        t.evaluate(wa("x"), on, ctx(busy = true))
        assertEquals(1, t.pendingCatchUpCount())
        assertNull(t.takeCatchUp(on, ctx(minute = 23 * 60 + 5)))
        assertEquals(0, t.pendingCatchUpCount())
    }

    @Test fun quietWindow_crossingMidnight() {
        assertTrue(NotificationTriage.inWindow(23 * 60, 22 * 60, 6 * 60))
        assertTrue(NotificationTriage.inWindow(2 * 60, 22 * 60, 6 * 60))
        assertFalse(NotificationTriage.inWindow(12 * 60, 22 * 60, 6 * 60))
        assertTrue(NotificationTriage.inWindow(12 * 60, 7 * 60, 22 * 60))
        assertFalse(NotificationTriage.inWindow(22 * 60, 7 * 60, 22 * 60))
    }

    // ---------- Speech ----------

    @Test fun fullLevel_readsSenderAppAndMessage() {
        assertEquals("Riya on WhatsApp: running 10 min late.", NotificationSpeech.compose(wa("running 10 min late"), ContentLevel.FULL, false))
    }

    @Test fun groupMessage_namesSenderAndGroup() {
        val n = wa("who's bringing cake?", sender = "Mom").copy(conversationTitle = "Family 🏡", isGroupConversation = true)
        assertEquals("Mom in Family: who's bringing cake?", NotificationSpeech.compose(n, ContentLevel.FULL, false))
    }

    @Test fun hideContentPrivacy_capsAtSenderLevel() {
        assertEquals("Message from Riya on WhatsApp.", NotificationSpeech.compose(wa("secret plans"), ContentLevel.FULL, hideContent = true))
        assertEquals("New WhatsApp message.", NotificationSpeech.compose(wa("secret"), ContentLevel.APP_ONLY, false))
    }

    @Test fun verificationCodes_areAnnounced_butNeverRead() {
        val sms = IncomingNotification("sms|1", "com.google.android.apps.messaging", "Messages", "HDFCBK",
            "Your OTP for login is 482913. Do not share it with anyone.", category = "msg", postedAt = clock, sender = "HDFCBK")
        val spoken = NotificationSpeech.compose(sms, ContentLevel.FULL, false)
        assertEquals("Verification code from Messages. I won't read it out.", spoken)
        assertFalse(spoken.contains("482913"))
        assertEquals("Verification code (hidden)", NotificationSpeech.preview(sms, false))
        assertNull(NotificationSuggestions.extract(sms))
    }

    @Test fun bodies_dropLinksEmojiAndLongNumbers_keepOtherScripts() {
        val n = wa("देखो 😂😂 https://x.co/abc call 9876543210 now!!!")
        val s = NotificationSpeech.compose(n, ContentLevel.FULL, false)
        assertTrue(s, s.contains("देखो"))
        assertFalse(s, s.contains("😂"))
        assertFalse(s, s.contains("http"))
        assertFalse(s, s.contains("9876543210"))
        assertTrue(s, s.contains("a link"))
        assertTrue(s, s.contains("a number"))
        assertTrue(s, s.endsWith("now!"))
    }

    @Test fun email_and_genericApps_readNaturally() {
        val mail = IncomingNotification("g|1", "com.google.android.gm", "Gmail", "Arjun Mehta", "Q3 numbers attached",
            category = "email", postedAt = clock)
        assertEquals("Email from Arjun Mehta: Q3 numbers attached.", NotificationSpeech.compose(mail, ContentLevel.FULL, false))
        val shop = IncomingNotification("s|1", "com.shop", "Shop", "Order shipped", "Arrives Tuesday", postedAt = clock)
        assertEquals("Shop. Order shipped. Arrives Tuesday.", NotificationSpeech.compose(shop, ContentLevel.FULL, false))
        assertEquals("New notification from Shop.", NotificationSpeech.compose(shop, ContentLevel.SENDER_ONLY, false))
    }

    @Test fun messageCountersAndLongBodies_areTidied() {
        val n = wa("x".repeat(10) + " " + "word ".repeat(80), sender = "Riya (3 messages)")
        val s = NotificationSpeech.compose(n, ContentLevel.FULL, false)
        assertTrue(s, s.startsWith("Riya on WhatsApp: "))
        assertTrue(s, s.length < 260)
        assertTrue(s, s.endsWith("…") || s.endsWith("."))
    }

    // ---------- Suggestions ----------

    @Test fun callMe_becomesCallTheSender() {
        val sug = NotificationSuggestions.extract(wa("can you call me back at 6pm?"))
        assertNotNull(sug)
        assertEquals("Call Riya", sug!!.title)
        assertEquals("at 6pm", sug.dueText)
        assertTrue(sug.summary, sug.summary.startsWith("From WhatsApp · Riya: Call Riya"))
    }

    @Test fun request_isPersonalised() {
        val sug = NotificationSuggestions.extract(wa("please send me the notes by tonight"))
        assertNotNull(sug)
        assertEquals("Send Riya the notes", sug!!.title)
        assertEquals("tonight", sug.dueText)
    }

    @Test fun eventMove_withTime_becomesEventSuggestion() {
        val slack = IncomingNotification("sl|1", "com.Slack", "Slack", "Arjun", "standup moved to 11:30 tomorrow",
            category = "msg", postedAt = clock, sender = "Arjun")
        val sug = NotificationSuggestions.extract(slack)
        assertNotNull(sug)
        assertEquals("Standup with Arjun", sug!!.title)
        assertEquals("11:30 tomorrow", sug.dueText)
    }

    @Test fun deadline_fromBillNotification() {
        val bill = IncomingNotification("b|1", "com.power", "PowerCo", "Electricity bill", "Your bill of Rs 1,240 is due on 25 Nov",
            category = "reminder", postedAt = clock)
        val sug = NotificationSuggestions.extract(bill)
        assertNotNull(sug)
        assertEquals("Deadline: Electricity bill", sug!!.title)
        assertEquals("25 Nov", sug.dueText)
    }

    @Test fun smallTalk_andPromotions_produceNoSuggestion() {
        assertNull(NotificationSuggestions.extract(wa("haha nice one")))
        assertNull(NotificationSuggestions.extract(wa("good morning!")))
        val promo = IncomingNotification("p|1", "com.shop", "Shop", "Sale", "Please check out our sale tomorrow at 5pm",
            category = "promo", postedAt = clock)
        assertNull(NotificationSuggestions.extract(promo))
        assertNull("an amount after 'at' is not a time", NotificationSuggestions.timeSpan("selling at 500 rs"))
    }

    @Test fun timeSpan_mergesAdjacentTokens() {
        assertEquals("tomorrow at 5pm", NotificationSuggestions.timeSpan("see you tomorrow at 5pm ok"))
        assertEquals("next monday", NotificationSuggestions.timeSpan("report by next monday please"))
        assertEquals("in 20 mins", NotificationSuggestions.timeSpan("leaving in 20 mins"))
    }

    // ---------- Engine + feed ----------

    @Test fun engine_feedsSpokenAndHeld_butNotDropped() {
        var id = 0
        val engine = NotificationReaderEngine(newId = { "h${id++}" })
        val spoken = engine.onPosted(wa("hello"), on, ctx())
        assertEquals("Riya on WhatsApp: hello.", spoken.speech)
        val held = engine.onPosted(wa("are you there"), on, ctx(inCall = true))
        assertNull(held.speech)
        val dropped = engine.onPosted(wa("bg").copy(isOngoing = true), on, ctx())
        assertNull(dropped.heard)
        val feed = engine.feed.snapshot()
        assertEquals(2, feed.size)
        assertEquals("are you there", feed[0].preview)
        assertEquals(SkipReason.IN_CALL, feed[0].holdReason)
        assertTrue(feed[1].spoken)
    }

    @Test fun engine_hideContent_keepsPreviewPrivate() {
        val engine = NotificationReaderEngine()
        val out = engine.onPosted(wa("the wifi password is hunter2"), on, ctx(hide = true))
        assertEquals("Message from Riya on WhatsApp.", out.speech)
        assertEquals("New message", out.heard!!.preview)
    }

    @Test fun feed_isBoundedAndNewestFirst() {
        val f = HeardFeed(capacity = 3)
        repeat(5) { i -> f.add(HeardNotification("$i", i.toLong(), "p", "App", null, "m$i", true, null)) }
        assertEquals(listOf("4", "3", "2"), f.snapshot().map { it.id })
    }

    @Test fun settings_roundTrip_andSurviveGarbage() {
        val s = NotificationReadSettings(
            enabled = true, speak = false, contentLevel = ContentLevel.SENDER_ONLY, scope = AppScope.ALL,
            allowedPackages = setOf("b", "a"), blockedPackages = setOf("z"), headphonesOnly = true,
            respectQuietHours = false, captureSuggestions = false, maxPerMinute = 7,
        )
        val map = s.toSettings()
        assertEquals(s, NotificationReadSettings.fromSettings { map[it] })
        val junk = mapOf(NotificationReadSettings.KEY_CONTENT to "loud", NotificationReadSettings.KEY_MAX_PER_MIN to "-9")
        val parsed = NotificationReadSettings.fromSettings { junk[it] }
        assertEquals(ContentLevel.FULL, parsed.contentLevel)
        assertEquals(1, parsed.maxPerMinute)
        assertFalse(parsed.enabled)
    }
}
