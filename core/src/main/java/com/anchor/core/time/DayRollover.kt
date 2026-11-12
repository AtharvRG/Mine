// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.time

import com.anchor.core.models.DayRule
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

object DayRollover {

    const val DEFAULT_START_MINUTE = 240 // 04:00 AM
    const val DEFAULT_END_MINUTE = 1320   // 22:00 PM

    fun defaultRule(nowUtc: Long = 0L): DayRule {
        return DayRule(
            id = "default",
            effectiveFrom = 0L,
            startMinute = DEFAULT_START_MINUTE,
            endMinute = DEFAULT_END_MINUTE,
            tzId = TimeZone.getDefault().id,
            createdAt = nowUtc
        )
    }

    fun computeDayKey(tsUtc: Long, rule: DayRule): String {
        val tz = safeTimeZone(rule.tzId)
        val tzOffset = tz.getOffset(tsUtc)
        val localTs = tsUtc + tzOffset
        val rolloverAdjusted = localTs - (rule.startMinute * 60_000L)
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return sdf.format(Date(rolloverAdjusted))
    }

    fun getLogicalDayStart(tsUtc: Long, rule: DayRule): Long {
        val tz = safeTimeZone(rule.tzId)
        val cal = Calendar.getInstance(tz).apply { timeInMillis = tsUtc }
        val currentMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (currentMin < rule.startMinute) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        cal.set(Calendar.HOUR_OF_DAY, rule.startMinute / 60)
        cal.set(Calendar.MINUTE, rule.startMinute % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun isInDayWindow(tsUtc: Long, rule: DayRule): Boolean {
        val tz = safeTimeZone(rule.tzId)
        val cal = Calendar.getInstance(tz).apply { timeInMillis = tsUtc }
        val currentMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (rule.startMinute <= rule.endMinute) {
            currentMin in rule.startMinute..rule.endMinute
        } else {
            currentMin >= rule.startMinute || currentMin <= rule.endMinute
        }
    }

    fun getNextRollover(
        nowUtc: Long,
        currentRule: DayRule,
        targetTzId: String? = null,
        targetStartMinute: Int? = null
    ): Long {
        val currentTz = safeTimeZone(currentRule.tzId)
        val cal = Calendar.getInstance(currentTz).apply { timeInMillis = nowUtc }

        // Today's rollover boundary in current timezone
        cal.set(Calendar.HOUR_OF_DAY, currentRule.startMinute / 60)
        cal.set(Calendar.MINUTE, currentRule.startMinute % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // If now is before today's rollover, the next rollover is today at startMinute
        if (nowUtc < cal.timeInMillis) {
            return cal.timeInMillis
        }

        // Otherwise, next rollover under current rule is tomorrow at startMinute
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val nextRolloverCurrent = cal.timeInMillis

        if (targetTzId != null && targetTzId != currentRule.tzId) {
            // "Changing the timezone also takes effect at the next day start computed in the new zone."
            val targetTz = safeTimeZone(targetTzId)
            val startMin = targetStartMinute ?: currentRule.startMinute
            val targetCal = Calendar.getInstance(targetTz).apply { timeInMillis = nowUtc }
            targetCal.set(Calendar.HOUR_OF_DAY, startMin / 60)
            targetCal.set(Calendar.MINUTE, startMin % 60)
            targetCal.set(Calendar.SECOND, 0)
            targetCal.set(Calendar.MILLISECOND, 0)
            if (targetCal.timeInMillis <= nowUtc) {
                targetCal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return targetCal.timeInMillis
        }

        return nextRolloverCurrent
    }

    fun scheduleNextRule(
        currentRule: DayRule,
        newStartMinute: Int,
        newEndMinute: Int = DEFAULT_END_MINUTE,
        newTzId: String,
        nowUtc: Long
    ): DayRule {
        val nextRollover = getNextRollover(
            nowUtc = nowUtc,
            currentRule = currentRule,
            targetTzId = newTzId,
            targetStartMinute = newStartMinute
        )
        return DayRule(
            id = "rule_${UUID.randomUUID()}",
            effectiveFrom = nextRollover,
            startMinute = newStartMinute,
            endMinute = newEndMinute,
            tzId = newTzId,
            createdAt = nowUtc
        )
    }

    fun safeTimeZone(tzId: String): TimeZone {
        if (tzId.equals("GMT", ignoreCase = true) || tzId.equals("UTC", ignoreCase = true)) {
            return TimeZone.getTimeZone(tzId)
        }
        val tz = TimeZone.getTimeZone(tzId)
        return if (tz.id == "GMT") TimeZone.getDefault() else tz
    }
}
