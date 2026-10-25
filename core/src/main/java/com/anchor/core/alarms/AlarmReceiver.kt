// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.core.alarms

object AlarmReceiver {
    @Volatile
    var lastFiredId: String? = null
}
