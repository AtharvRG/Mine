// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.notifications

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Receives posted notifications once the user grants Mine notification access.
 *
 * It does nothing on its own: every notification goes to [NotificationReader], which decides
 * (with the user's settings and ambient listening state) whether it is read aloud. Nothing is
 * stored from here except suggestions the user can review and reject.
 */
class MineNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationReader.onListenerConnected(applicationContext, connected = true)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationReader.onListenerConnected(applicationContext, connected = false)
        // The system unbinds us under memory pressure; ask to be rebound so reading resumes.
        runCatching { requestRebind(ComponentName(this, MineNotificationListener::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        sbn ?: return
        runCatching { NotificationReader.onPosted(applicationContext, sbn, rankingMap) }
    }
}
