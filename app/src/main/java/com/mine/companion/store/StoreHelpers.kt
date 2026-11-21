package com.mine.companion.store

/*
 * Pure helpers the store uses to talk to the engine in its own words. Kept free of Android so
 * they are unit-tested on the JVM (StoreHelpersTest).
 */

internal fun personaFromEngine(id: String): PersonaType = when (id) {
    "cofounder" -> PersonaType.cofounder
    "coach" -> PersonaType.coach
    "chill_friend", "chill" -> PersonaType.chill
    "minimal" -> PersonaType.minimal
    else -> PersonaType.companion
}

internal fun personaToEngine(p: PersonaType): String = when (p) {
    PersonaType.chill -> "chill_friend"
    else -> p.name
}

internal fun minuteLabel(m: Int): String = "%02d:%02d".format(((m / 60) % 24 + 24) % 24, ((m % 60) + 60) % 60)

internal fun parseMinute(label: String): Int? {
    val m = Regex("^(\\d{1,2}):(\\d{2})$").find(label.trim()) ?: return null
    val h = m.groupValues[1].toInt()
    val mm = m.groupValues[2].toInt()
    return if (h in 0..23 && mm in 0..59) h * 60 + mm else null
}

/** "Today 20:00" → "today at 20:00", "In 1h" → "in 1 hour": the date parser's own words. */
internal fun naturalWhen(label: String): String {
    val t = label.trim()
    if (t.isEmpty()) return t
    Regex("(?i)^(today|tomorrow)\\s+(\\d{1,2}(:\\d{2})?\\s*(am|pm)?)$").find(t)?.let {
        return "${it.groupValues[1].lowercase()} at ${it.groupValues[2]}"
    }
    Regex("(?i)^in\\s*(\\d+)\\s*h$").find(t)?.let { return "in ${it.groupValues[1]} hours" }
    Regex("(?i)^in\\s*(\\d+)\\s*m(in)?$").find(t)?.let { return "in ${it.groupValues[1]} minutes" }
    Regex("^\\d{1,2}:\\d{2}$").find(t)?.let { return "at $t" }
    return t.lowercase()
}

/** Turns a pause choice ("30m", "1h", "Until tomorrow", null) into an end time. */
internal fun pauseUntil(choice: String?, now: Long = System.currentTimeMillis()): Long? {
    val c = choice?.trim()?.lowercase() ?: return null
    Regex("^(\\d+)\\s*m(in)?$").find(c)?.let { return now + it.groupValues[1].toLong() * 60_000 }
    Regex("^(\\d+)\\s*h(our|ours|r|rs)?$").find(c)?.let { return now + it.groupValues[1].toLong() * 3_600_000 }
    if ("tomorrow" in c || "today" in c || "evening" in c || "night" in c) {
        val zone = java.time.ZoneId.systemDefault()
        val base = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = if ("tomorrow" in c) base.plusDays(1).atTime(8, 0) else base.atTime(23, 59)
        return day.atZone(zone).toInstant().toEpochMilli()
    }
    return now + 3_600_000
}
