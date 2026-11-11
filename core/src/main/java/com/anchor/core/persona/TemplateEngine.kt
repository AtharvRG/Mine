// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.persona

import kotlin.math.abs

enum class TriggerType {
    REMINDER_START,
    REMINDER_CHECK,
    REMINDER_DEADLINE,
    NUDGE_OVERDUE,
    NUDGE_STALE,
    CHECKIN_MORNING,
    CHECKIN_EVENING,
    CHECKIN_WEEKLY
}

/**
 * Offline template engine rendering trigger notifications and check-ins in the persona's voice
 * (Blueprint 41 §3.7 / Blueprint 31 §3.9 / Blueprint 24 §3.5).
 */
class TemplateEngine {

    companion object {
        val TEMPLATES = mapOf(
            // --- REMINDER_START ---
            (TriggerType.REMINDER_START to PersonaTone.WARM) to listOf(
                "Time to begin: {title}.",
                "Starting now: {title}. You've got this!",
                "Ready to start {title}?"
            ),
            (TriggerType.REMINDER_START to PersonaTone.DIRECT_SARCASTIC) to listOf(
                "Clock's ticking on {title}. Let's get moving.",
                "Start time for {title}. No stalling.",
                "{title} starts now. One step at a time."
            ),
            (TriggerType.REMINDER_START to PersonaTone.STRUCTURED) to listOf(
                "Scheduled start: {title}.",
                "Initiating {title} per schedule.",
                "Action item starting now: {title}."
            ),
            (TriggerType.REMINDER_START to PersonaTone.CASUAL) to listOf(
                "Hey, time for {title}!",
                "{title} is up now. Let's do it.",
                "Ready or not, it's time for {title}."
            ),
            (TriggerType.REMINDER_START to PersonaTone.MINIMAL) to listOf(
                "Start: {title}.",
                "Now: {title}.",
                "{title}."
            ),

            // --- REMINDER_CHECK ---
            (TriggerType.REMINDER_CHECK to PersonaTone.WARM) to listOf(
                "How is {title} going?",
                "Quick check on {title} — hope it's going smoothly!",
                "Making good progress on {title}?"
            ),
            (TriggerType.REMINDER_CHECK to PersonaTone.DIRECT_SARCASTIC) to listOf(
                "Status check: did you actually start {title}?",
                "Still working on {title}, or browsing memes?",
                "Where are we on {title}?"
            ),
            (TriggerType.REMINDER_CHECK to PersonaTone.STRUCTURED) to listOf(
                "Progress check: {title}.",
                "Milestone review for {title}.",
                "Status update required: {title}."
            ),
            (TriggerType.REMINDER_CHECK to PersonaTone.CASUAL) to listOf(
                "How's {title} coming along?",
                "Quick ping on {title}.",
                "Making headway on {title}?"
            ),
            (TriggerType.REMINDER_CHECK to PersonaTone.MINIMAL) to listOf(
                "Status: {title}?",
                "Check: {title}.",
                "Update: {title}."
            ),

            // --- REMINDER_DEADLINE ---
            (TriggerType.REMINDER_DEADLINE to PersonaTone.WARM) to listOf(
                "{title} is due now.",
                "Deadline reached for {title}. Need any help wrapping up?",
                "Gentle reminder: {title} is due."
            ),
            (TriggerType.REMINDER_DEADLINE to PersonaTone.DIRECT_SARCASTIC) to listOf(
                "{title} is due right now. Pencils down.",
                "Deadline's here for {title}. Time to deliver.",
                "{title} hit its deadline. Let's close it out."
            ),
            (TriggerType.REMINDER_DEADLINE to PersonaTone.STRUCTURED) to listOf(
                "Deadline reached: {title}.",
                "Target due time reached for {title}.",
                "Final deadline: {title}."
            ),
            (TriggerType.REMINDER_DEADLINE to PersonaTone.CASUAL) to listOf(
                "{title} is due now!",
                "Heads up, {title} deadline is right now.",
                "Time's up for {title}."
            ),
            (TriggerType.REMINDER_DEADLINE to PersonaTone.MINIMAL) to listOf(
                "Due: {title}.",
                "Deadline: {title}.",
                "{title} (due)."
            ),

            // --- NUDGE_OVERDUE ---
            (TriggerType.NUDGE_OVERDUE to PersonaTone.WARM) to listOf(
                "Still on your list: {title}.",
                "Whenever you're ready, {title} is waiting for you.",
                "Gentle nudge for {title} when you have a moment."
            ),
            (TriggerType.NUDGE_OVERDUE to PersonaTone.DIRECT_SARCASTIC) to listOf(
                "{title} is overdue. Still planning to do it today?",
                "Overdue alert: {title}. Want to tackle it or reschedule?",
                "You parked {title}. Let's finish it or drop it."
            ),
            (TriggerType.NUDGE_OVERDUE to PersonaTone.STRUCTURED) to listOf(
                "Overdue action item: {title}.",
                "Pending resolution: {title} is past due.",
                "Action required on overdue item: {title}."
            ),
            (TriggerType.NUDGE_OVERDUE to PersonaTone.CASUAL) to listOf(
                "Hey, {title} is still hanging out on your list.",
                "Still got {title} to wrap up.",
                "{title} is waiting whenever you're free."
            ),
            (TriggerType.NUDGE_OVERDUE to PersonaTone.MINIMAL) to listOf(
                "Overdue: {title}.",
                "Pending: {title}.",
                "{title} (overdue)."
            ),

            // --- NUDGE_STALE ---
            (TriggerType.NUDGE_STALE to PersonaTone.WARM) to listOf(
                "It's been a little while since you touched {title}. Still relevant?",
                "Just checking in on {title} — do you want to keep this open?",
                "Take your time, but {title} is still here if you need it."
            ),
            (TriggerType.NUDGE_STALE to PersonaTone.DIRECT_SARCASTIC) to listOf(
                "{title} is gathering dust. Keep or delete?",
                "Been a few days on {title}. Are we doing this or what?",
                "Stale item check: {title}. Let's make a call."
            ),
            (TriggerType.NUDGE_STALE to PersonaTone.STRUCTURED) to listOf(
                "Review stale item: {title}.",
                "Stale task audit: determine status of {title}.",
                "Inactive item requires triage: {title}."
            ),
            (TriggerType.NUDGE_STALE to PersonaTone.CASUAL) to listOf(
                "{title} has been sitting there for a bit.",
                "Still care about {title}?",
                "Quick check: is {title} still on?"
            ),
            (TriggerType.NUDGE_STALE to PersonaTone.MINIMAL) to listOf(
                "Stale: {title}.",
                "Review: {title}.",
                "{title}?"
            ),

            // --- CHECKIN_MORNING ---
            (TriggerType.CHECKIN_MORNING to PersonaTone.WARM) to listOf(
                "Good morning! You have {count} items today. Have a wonderful start!",
                "Morning check-in: {count} things on your agenda today.",
                "Good morning! Let's take today one step at a time."
            ),
            (TriggerType.CHECKIN_MORNING to PersonaTone.DIRECT_SARCASTIC) to listOf(
                "Morning. You've got {count} things to tackle today. Let's get to work.",
                "Rise and shine. {count} items waiting. What's first?",
                "Day starts now. {count} commitments on the board."
            ),
            (TriggerType.CHECKIN_MORNING to PersonaTone.STRUCTURED) to listOf(
                "Daily briefing: {count} active agenda items scheduled.",
                "Morning status report: {count} tasks today.",
                "Day start initialization: {count} pending items."
            ),
            (TriggerType.CHECKIN_MORNING to PersonaTone.CASUAL) to listOf(
                "Morning! Got {count} things going on today.",
                "Hey there, {count} things on deck today.",
                "Good morning! Ready for the day?"
            ),
            (TriggerType.CHECKIN_MORNING to PersonaTone.MINIMAL) to listOf(
                "Morning: {count} items today.",
                "Agenda: {count} items.",
                "Day start. {count} tasks."
            ),

            // --- CHECKIN_EVENING ---
            (TriggerType.CHECKIN_EVENING to PersonaTone.WARM) to listOf(
                "Evening check-in. Great job today! Let's see how things wrapped up.",
                "Time to wind down. Let's review what was finished today.",
                "Good evening. Hope your day went well!"
            ),
            (TriggerType.CHECKIN_EVENING to PersonaTone.DIRECT_SARCASTIC) to listOf(
                "Evening audit. Let's see what you actually finished today.",
                "Day's wrapping up. Honest review time.",
                "Evening check. How much did we get done?"
            ),
            (TriggerType.CHECKIN_EVENING to PersonaTone.STRUCTURED) to listOf(
                "Evening review: tallying completed milestones.",
                "Daily closing summary: review pending and completed items.",
                "End-of-day reconciliation."
            ),
            (TriggerType.CHECKIN_EVENING to PersonaTone.CASUAL) to listOf(
                "Evening! How'd the day shake out?",
                "Wrapping up today. Good job on what you got done.",
                "Day's over! Time to relax."
            ),
            (TriggerType.CHECKIN_EVENING to PersonaTone.MINIMAL) to listOf(
                "Evening review.",
                "Day closing.",
                "End of day summary."
            ),

            // --- CHECKIN_WEEKLY ---
            (TriggerType.CHECKIN_WEEKLY to PersonaTone.WARM) to listOf(
                "Weekly reflection: here is your week at a glance. Proud of your efforts!",
                "Week in review: celebrating your wins and planning ahead.",
                "Weekly check-in: how are you feeling about this week?"
            ),
            (TriggerType.CHECKIN_WEEKLY to PersonaTone.DIRECT_SARCASTIC) to listOf(
                "Weekly audit. Let's look at the numbers and see what moved.",
                "End of week scorecard. Time to review progress.",
                "Weekly debrief: what worked, what stalled, what's next."
            ),
            (TriggerType.CHECKIN_WEEKLY to PersonaTone.STRUCTURED) to listOf(
                "Weekly retrospective: aggregate performance and review.",
                "Weekly cadence report: completed vs carryover items.",
                "End of week systems review."
            ),
            (TriggerType.CHECKIN_WEEKLY to PersonaTone.CASUAL) to listOf(
                "Week in review! Let's see how things went.",
                "Weekly recap time.",
                "Look at that week! Here's the rundown."
            ),
            (TriggerType.CHECKIN_WEEKLY to PersonaTone.MINIMAL) to listOf(
                "Weekly review.",
                "Week summary.",
                "Weekly stats."
            )
        )
    }

    /**
     * Renders a template text interpolated with provided slots and styled by the active persona.
     */
    fun render(
        trigger: TriggerType,
        tone: PersonaTone,
        slots: Map<String, String>,
        seed: Long = 0L,
        languageCode: String? = "en"
    ): String {
        val pack = TemplatePackRegistry.getPack(languageCode) ?: EnglishTemplatePack
        val variants = pack.getTemplates(trigger, tone)
            ?: EnglishTemplatePack.getTemplates(trigger, tone)
            ?: EnglishTemplatePack.getTemplates(trigger, PersonaTone.WARM)
            ?: listOf("{title}")

        val idx = Math.floorMod((trigger.hashCode() * 31 + tone.hashCode() * 17 + seed).toInt(), variants.size)
        var text = variants[idx]

        for ((key, value) in slots) {
            text = text.replace("{$key}", value)
        }

        // Clean up any unmatched standard placeholders gracefully
        text = text.replace("{title}", "Item")
            .replace("{count}", "0")
            .replace("{due}", "")
            .replace("{name}", "")
            .trim()

        return text
    }

    fun render(
        trigger: TriggerType,
        persona: Persona,
        slots: Map<String, String>,
        seed: Long = 0L,
        languageCode: String? = "en"
    ): String = render(trigger, persona.tone, slots, seed, languageCode)
}
