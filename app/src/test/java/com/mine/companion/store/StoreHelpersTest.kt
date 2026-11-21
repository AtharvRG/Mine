package com.mine.companion.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreHelpersTest {

    @Test fun uiTimeLabels_becomeWordsTheDateParserKnows() {
        assertEquals("tomorrow at 09:00", naturalWhen("Tomorrow 09:00"))
        assertEquals("today at 8:30 pm", naturalWhen("Today 8:30 PM").lowercase())
        assertEquals("in 30 minutes", naturalWhen("In 30 min"))
        assertEquals("in 2 hours", naturalWhen("in 2h"))
        assertEquals("at 19:45", naturalWhen("19:45"))
        assertEquals("next friday", naturalWhen("Next Friday"))
        assertEquals("", naturalWhen("  "))
    }

    @Test fun pauseChoices_resolveToEndTimes() {
        val now = 1_000_000_000_000L
        assertNull(pauseUntil(null, now))
        assertEquals(now + 3_600_000L, pauseUntil("1h", now))
        assertEquals(now + 3 * 3_600_000L, pauseUntil("3h", now))
        assertEquals(now + 30 * 60_000L, pauseUntil("30m", now))
        assertTrue(pauseUntil("Until tomorrow", now)!! > now)
    }

    @Test fun dayWindowLabels_roundTrip() {
        assertEquals(7 * 60 + 30, parseMinute("07:30"))
        assertEquals("07:30", minuteLabel(7 * 60 + 30))
        assertNull(parseMinute("25:00"))
        assertNull(parseMinute("7pm"))
        assertEquals("00:00", minuteLabel(1440))
    }

    @Test fun personaIds_matchTheEngine() {
        assertEquals("chill_friend", personaToEngine(PersonaType.chill))
        assertEquals(PersonaType.chill, personaFromEngine("chill_friend"))
        PersonaType.entries.forEach { assertEquals(it, personaFromEngine(personaToEngine(it))) }
        assertEquals(PersonaType.companion, personaFromEngine("custom_123"))
    }

    @Test fun reducer_neverInventsContent() {
        val s0 = MineSeed.initial
        val s1 = mineReducer(s0, MineAction.SendChatMessage("hi"), now = 5L)
        assertEquals("hi", s1.todayMessages.single().text)
        assertTrue(s1.chatBusy)
        // Domain actions that the engine performs leave state untouched here.
        assertEquals(s0, mineReducer(s0, MineAction.QuickAddOfflineItem("gym at 7")))
        assertEquals(s0.inboxItems, mineReducer(s0, MineAction.ResolveInboxItem("x", true)).inboxItems)
        // Back at the root lands on Home rather than closing the app mid-flow.
        val deep = mineReducer(s0, MineAction.Navigate(ScreenId.S26))
        assertEquals(ScreenId.S14, mineReducer(deep, MineAction.GoBack).currentScreen)
    }
}
