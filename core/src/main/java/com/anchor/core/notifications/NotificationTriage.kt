// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.notifications

/**
 * Decides, for every posted notification, whether Mine reads it aloud, keeps it quietly in the
 * feed, or ignores it. One instance lives for the process; it remembers just enough (recent keys,
 * recent speech times, held bursts) to avoid repeating itself and to never flood the user.
 *
 * Order of checks matters and is pinned by tests:
 *  1. Hard drops (feature off, ambient off, our own app, ongoing, summaries, empty, silent reposts,
 *     non-message categories, muted or out-of-scope apps, low importance, duplicates).
 *  2. Soft holds — the notification is real news but this is not the moment to speak: speech off,
 *     a call, a conversation with Mine, quiet hours, DND, no headphones, rate limit.
 *  3. Speak.
 *
 * Thread-safe: the listener service delivers on a binder thread while the UI reads the feed.
 */
class NotificationTriage(
    private val dedupeWindowMs: Long = 10 * 60_000L,
    private val sameTextWindowMs: Long = 2 * 60_000L,
) {
    private data class Seen(val fingerprint: Int, val at: Long)

    private val seenByKey = LinkedHashMap<String, Seen>()
    private val seenByText = LinkedHashMap<String, Long>()
    private val spokenAt = ArrayDeque<Long>()
    private val heldBurst = ArrayList<IncomingNotification>()

    @Synchronized
    fun evaluate(n: IncomingNotification, s: NotificationReadSettings, ctx: ReaderContext): ReadDecision {
        prune(ctx.nowMs)

        if (!s.enabled) return ReadDecision.Drop(SkipReason.DISABLED)
        if (!ctx.ambientActive) return ReadDecision.Drop(SkipReason.AMBIENT_OFF)
        if (n.packageName == ctx.ownPackage) return ReadDecision.Drop(SkipReason.OWN_APP)
        if (n.isOngoing) return ReadDecision.Drop(SkipReason.ONGOING)
        if (n.isGroupSummary) return ReadDecision.Drop(SkipReason.GROUP_SUMMARY)
        if (n.body.isNullOrBlank() && n.title.isNullOrBlank()) return ReadDecision.Drop(SkipReason.EMPTY)
        if (n.isSilentUpdate) return ReadDecision.Drop(SkipReason.SILENT_UPDATE)
        if (n.category in IncomingNotification.NEVER_READ_CATEGORIES) return ReadDecision.Drop(SkipReason.CATEGORY)
        if (n.packageName in s.blockedPackages) return ReadDecision.Drop(SkipReason.BLOCKED_APP)

        val explicitlyAllowed = n.packageName in s.allowedPackages
        when (s.scope) {
            AppScope.ALLOWLIST -> if (!explicitlyAllowed) return ReadDecision.Drop(SkipReason.NOT_IN_SCOPE)
            AppScope.MESSAGES -> if (!explicitlyAllowed && !n.isMessaging && n.category != IncomingNotification.CATEGORY_MISSED_CALL) {
                return ReadDecision.Drop(SkipReason.NOT_IN_SCOPE)
            }
            AppScope.ALL -> Unit
        }
        if (!explicitlyAllowed) {
            if (n.category in IncomingNotification.LOW_VALUE_CATEGORIES) return ReadDecision.Drop(SkipReason.LOW_PRIORITY)
            val importance = n.importance ?: 3
            if (importance in 0..2) return ReadDecision.Drop(SkipReason.LOW_PRIORITY)
        }

        if (isDuplicate(n, ctx.nowMs)) return ReadDecision.Drop(SkipReason.DUPLICATE)

        // From here on the notification is news. Holds keep it in the feed without speaking.
        val hold = when {
            !s.speak -> SkipReason.SPEECH_OFF
            ctx.inCall -> SkipReason.IN_CALL
            ctx.conversationBusy -> SkipReason.CONVERSATION
            s.respectQuietHours && isQuiet(ctx) -> SkipReason.QUIET_HOURS
            ctx.dndActive && n.passesDnd != true -> SkipReason.DND
            s.headphonesOnly && !ctx.headphonesConnected -> SkipReason.NO_HEADPHONES
            spokenInLastMinute(ctx.nowMs) >= s.maxPerMinute -> SkipReason.RATE_LIMITED
            else -> null
        }
        if (hold != null) {
            if (hold == SkipReason.RATE_LIMITED || hold == SkipReason.CONVERSATION) {
                // Worth catching up on once the moment passes.
                heldBurst.add(n)
                if (heldBurst.size > MAX_BURST) heldBurst.removeAt(0)
            }
            return ReadDecision.Hold(hold)
        }

        spokenAt.addLast(ctx.nowMs)
        return ReadDecision.Speak(NotificationSpeech.compose(n, s.contentLevel, ctx.hideContent))
    }

    /**
     * Speaks the catch-up line for notifications held by the rate limit or a conversation, once
     * speaking is possible again. Returns null when there is nothing to catch up on, or when the
     * moment still is not right.
     */
    @Synchronized
    fun takeCatchUp(s: NotificationReadSettings, ctx: ReaderContext): String? {
        prune(ctx.nowMs)
        if (heldBurst.isEmpty()) return null
        if (!s.enabled || !s.speak || !ctx.ambientActive) {
            heldBurst.clear()
            return null
        }
        if (ctx.inCall || ctx.conversationBusy) return null
        if (s.respectQuietHours && isQuiet(ctx)) {
            heldBurst.clear()
            return null
        }
        if (s.headphonesOnly && !ctx.headphonesConnected) return null
        if (spokenInLastMinute(ctx.nowMs) >= s.maxPerMinute) return null
        val line = NotificationSpeech.burstSummary(heldBurst.toList(), ctx.hideContent)
        heldBurst.clear()
        if (line != null) spokenAt.addLast(ctx.nowMs)
        return line
    }

    @Synchronized
    fun pendingCatchUpCount(): Int = heldBurst.size

    /** Forget everything — used when the user turns the feature off or clears the feed. */
    @Synchronized
    fun reset() {
        seenByKey.clear(); seenByText.clear(); spokenAt.clear(); heldBurst.clear()
    }

    private fun isDuplicate(n: IncomingNotification, now: Long): Boolean {
        val body = (n.body ?: n.title).orEmpty().trim().lowercase()
        val fp = (n.sender.orEmpty() + "\u0000" + body).hashCode()
        val prev = seenByKey[n.key]
        val textKey = n.packageName + "\u0000" + body
        val prevText = seenByText[textKey]
        seenByKey.remove(n.key)
        seenByKey[n.key] = Seen(fp, now)
        seenByText.remove(textKey)
        seenByText[textKey] = now
        trim()
        if (prev != null && prev.fingerprint == fp && now - prev.at < dedupeWindowMs) return true
        if (prevText != null && body.isNotEmpty() && now - prevText < sameTextWindowMs) return true
        return false
    }

    private fun spokenInLastMinute(now: Long): Int = spokenAt.count { now - it < 60_000L }

    private fun isQuiet(ctx: ReaderContext): Boolean = !inWindow(ctx.minuteOfDay, ctx.dayStartMinute, ctx.dayEndMinute)

    private fun prune(now: Long) {
        while (spokenAt.isNotEmpty() && now - spokenAt.first() >= 60_000L) spokenAt.removeFirst()
        val keyIt = seenByKey.entries.iterator()
        while (keyIt.hasNext()) if (now - keyIt.next().value.at >= dedupeWindowMs) keyIt.remove()
        val textIt = seenByText.entries.iterator()
        while (textIt.hasNext()) if (now - textIt.next().value >= sameTextWindowMs) textIt.remove()
        heldBurst.removeAll { now - it.postedAt > BURST_TTL_MS }
    }

    private fun trim() {
        while (seenByKey.size > MAX_TRACKED) seenByKey.remove(seenByKey.keys.first())
        while (seenByText.size > MAX_TRACKED) seenByText.remove(seenByText.keys.first())
    }

    companion object {
        private const val MAX_TRACKED = 512
        private const val MAX_BURST = 40
        /** Held notifications older than this are stale news and are not caught up on. */
        const val BURST_TTL_MS = 15 * 60_000L

        /** True when [minute] lies inside [start, end), handling windows that cross midnight. */
        fun inWindow(minute: Int, start: Int, end: Int): Boolean {
            val m = ((minute % 1440) + 1440) % 1440
            if (start == end) return true
            return if (start < end) m in start until end else m >= start || m < end
        }
    }
}

/**
 * The in-memory "Heard while listening" list. Bounded and newest-first; nothing here is ever
 * written to disk, so turning the feature off or restarting the phone leaves no trace.
 */
class HeardFeed(private val capacity: Int = 60) {
    private val items = ArrayDeque<HeardNotification>()

    @Synchronized
    fun add(item: HeardNotification) {
        items.removeAll { it.id == item.id }
        items.addFirst(item)
        while (items.size > capacity) items.removeLast()
    }

    @Synchronized
    fun update(id: String, transform: (HeardNotification) -> HeardNotification) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) items[idx] = transform(items[idx])
    }

    @Synchronized
    fun get(id: String): HeardNotification? = items.firstOrNull { it.id == id }

    @Synchronized
    fun snapshot(): List<HeardNotification> = items.toList()

    @Synchronized
    fun clear() = items.clear()
}
