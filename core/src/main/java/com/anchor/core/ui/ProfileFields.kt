// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.ui

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The personal details onboarding asks for (quick and full setup alike). Each is stored as a
 * `profile.*` setting so the user can change it in About you, and turned into an identity Soul
 * fact when onboarding finishes, so the AI knows it from the first chat. Every field is optional.
 */
object ProfileFields {
    enum class Kind { TEXT, DATE, CHOICE }

    data class Field(
        val key: String,
        val label: String,
        val placeholder: String,
        val kind: Kind = Kind.TEXT,
        val choices: List<String> = emptyList(),
        val sensitive: Boolean = false,
    )

    val FULL_NAME = Field("profile.full_name", "Full name", "As it appears on things")
    val DOB = Field("profile.dob", "Date of birth", "e.g. 14 March 1998", Kind.DATE)
    val PRONOUNS = Field("profile.pronouns", "Pronouns", "", Kind.CHOICE, listOf("he/him", "she/her", "they/them"))
    val CITY = Field("profile.city", "Where you live", "City, country")
    val HOMETOWN = Field("profile.hometown", "Where you're from", "Optional")
    val OCCUPATION = Field("profile.occupation", "What you do", "Student, designer, founder…")
    val WORK_HOURS = Field("profile.work_hours", "Usual work or study hours", "e.g. 10 to 7, weekdays")
    val RELATIONSHIP = Field("profile.relationship", "Relationship", "", Kind.CHOICE, listOf("Single", "In a relationship", "Married", "Prefer not to say"), sensitive = true)
    val ABOUT = Field("profile.about", "Anything else Mine should know", "Pets, people, goals, quirks…")

    /** When "1", the facts made from these details never leave the phone. */
    const val KEY_LOCAL_ONLY = "profile.local_only"

    val ALL = listOf(FULL_NAME, DOB, PRONOUNS, CITY, HOMETOWN, OCCUPATION, WORK_HOURS, RELATIONSHIP, ABOUT)
    val ALL_KEYS: List<String> = ALL.map { it.key } + KEY_LOCAL_ONLY

    private val DOB_FORMATS = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("d/M/uuuu"),
        DateTimeFormatter.ofPattern("d-M-uuuu"),
        DateTimeFormatter.ofPattern("d.M.uuuu"),
        DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("MMMM d uuuu", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("MMM d uuuu", Locale.ENGLISH),
    )

    /**
     * Reads a date of birth the way people type it: "1998-03-14", "14/3/1998", "14 March 1998",
     * "March 14th, 1998". Day-first for numbers. Null for anything that isn't a real past date.
     */
    fun parseDob(text: String, today: LocalDate): LocalDate? {
        val t = text.trim().replace(Regex("""(\d)(st|nd|rd|th)\b""", RegexOption.IGNORE_CASE), "$1")
            .replace(",", " ").replace(Regex("""\s+"""), " ")
            .split(' ').joinToString(" ") { w -> w.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) } }
        for (f in DOB_FORMATS) {
            val d = runCatching { LocalDate.parse(t, f.withResolverStyle(java.time.format.ResolverStyle.STRICT)) }.getOrNull()
                ?: runCatching { LocalDate.parse(t, f) }.getOrNull()
            if (d != null) return d.takeIf { it.isBefore(today) && it.year >= today.year - 120 }
        }
        return null
    }

    fun ageOn(dob: LocalDate, today: LocalDate): Int = java.time.Period.between(dob, today).years

    /** One plain sentence per filled field, ready to be a Soul fact. Blank and invalid values are skipped. */
    fun soulStatements(values: Map<String, String>, today: LocalDate): List<Pair<Field, String>> = buildList {
        fun v(f: Field) = values[f.key]?.trim()?.takeIf { it.isNotEmpty() }
        v(FULL_NAME)?.let { add(FULL_NAME to "Full name: $it.") }
        v(DOB)?.let { raw ->
            parseDob(raw, today)?.let { d ->
                val month = d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                add(DOB to "Birthday is ${d.dayOfMonth} $month (born ${d.year}).")
            }
        }
        v(PRONOUNS)?.let { add(PRONOUNS to "Pronouns: $it.") }
        v(CITY)?.let { add(CITY to "Lives in $it.") }
        v(HOMETOWN)?.let { add(HOMETOWN to "Originally from $it.") }
        v(OCCUPATION)?.let { add(OCCUPATION to "Occupation: $it.") }
        v(WORK_HOURS)?.let { add(WORK_HOURS to "Usual work or study hours: $it.") }
        v(RELATIONSHIP)?.takeIf { it != "Prefer not to say" }?.let { add(RELATIONSHIP to "Relationship status: $it.") }
        v(ABOUT)?.let { add(ABOUT to it) }
    }
}
