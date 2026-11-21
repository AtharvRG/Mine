// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.models

import kotlinx.serialization.Serializable

@Serializable
data class DayBlock(
    val dayKey: String,
    val idx: Int,
    val startMsgId: String,
    val startTs: Long,
    val endTs: Long,
    val msgCount: Int,
    val partOfDay: String? = null
)

@Serializable
data class ExportLogRecord(
    val id: String,
    val ts: Long,
    val kind: String,
    val optionsJson: String? = null,
    val countsJson: String? = null,
    val bytes: Long? = null,
    val verified: Boolean = false,
    val note: String? = null
)

@Serializable
data class CheckinStateRecord(
    val kind: String,
    val dayKey: String,
    val status: String,
    val msgId: String? = null,
    val scheduledFor: Long? = null,
    val deliveredAt: Long? = null
)

@Serializable
data class NudgeLogRecord(
    val id: String,
    val tsUtc: Long,
    val triggerType: String,
    val subjectRefs: String? = null,
    val score: Double? = null,
    val threshold: Double? = null,
    val gatesJson: String? = null,
    val decision: String,
    val delivery: String? = null,
    val msgId: String? = null,
    val textSource: String? = null,
    val outcome: String? = null,
    val latencyMs: Long? = null,
    val reasonJson: String? = null
)
