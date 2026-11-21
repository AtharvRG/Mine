// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.notifications

import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.TelephonyManager
import com.anchor.core.notifications.HeardNotification
import com.anchor.core.notifications.IncomingNotification
import com.anchor.core.notifications.NotificationReadSettings
import com.anchor.core.notifications.NotificationReaderEngine
import com.anchor.core.notifications.NotificationSuggestionWriter
import com.anchor.core.notifications.ReaderContext
import com.anchor.core.time.DayRollover
import com.anchor.mine.MineApp
import com.anchor.mine.ambient.AmbientForegroundService
import com.anchor.mine.ambient.AmbientStatusBus
import com.anchor.mine.voice.AudioRecorderHelper
import com.anchor.mine.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads notifications aloud while ambient listening is on.
 *
 * The listener service hands every posted notification here. Triage (what to read, what to
 * hold, what to ignore) is the pure [NotificationReaderEngine] in `:core`; this class only
 * gathers the device context it needs (call state, headphones, DND, the user's day window),
 * speaks through [Speaker], and saves suggestions to the ambient review list.
 */
object NotificationReader {

    data class Status(
        /** The user granted Mine notification access in system settings. */
        val accessGranted: Boolean = false,
        /** The system has bound our listener (access alone does not guarantee this). */
        val listenerConnected: Boolean = false,
        val settings: NotificationReadSettings = NotificationReadSettings(),
        val feed: List<HeardNotification> = emptyList(),
        val pendingCatchUp: Int = 0,
        /** Apps that posted recently, for the per-app picker: package → label. */
        val recentApps: Map<String, String> = emptyMap(),
    )

    private val engine = NotificationReaderEngine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val labelCache = ConcurrentHashMap<String, String>()
    private val recentApps = ConcurrentHashMap<String, String>()
    private var catchUpJob: Job? = null

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var settings = NotificationReadSettings()
    @Volatile private var hideContent = false
    @Volatile private var dayStartMinute = 7 * 60
    @Volatile private var dayEndMinute = 22 * 60
    @Volatile private var conversationBusy = false

    /** Cheap check for the ambient notification text; never touches storage. */
    fun isReadingEnabledCached(): Boolean = settings.enabled

    /** Called whenever the engine state refreshes, so triage uses current preferences. */
    fun updateFromEngine(
        newSettings: NotificationReadSettings,
        hideNotificationContent: Boolean,
        dayStart: Int,
        dayEnd: Int,
        context: Context,
    ) {
        // Called on every engine update (≈10×/s while a reply streams): do nothing unless something changed.
        if (newSettings == settings && hideNotificationContent == hideContent && dayStart == dayStartMinute && dayEnd == dayEndMinute) return
        val wasEnabled = settings.enabled
        settings = newSettings
        hideContent = hideNotificationContent
        dayStartMinute = dayStart
        dayEndMinute = dayEnd
        if (wasEnabled && !newSettings.enabled) {
            engine.reset()
            Speaker.stopNotifications()
        }
        publish(context)
        if (wasEnabled != newSettings.enabled) AmbientForegroundService.refresh(context)
    }

    /** The hands-free conversation is listening or speaking: hold read-outs until it ends. */
    fun setConversationBusy(busy: Boolean, context: Context) {
        conversationBusy = busy
        if (busy) Speaker.stopNotifications() else scheduleCatchUp(context, 1_500)
    }

    fun refreshAccess(context: Context) = publish(context)

    internal fun onListenerConnected(context: Context, connected: Boolean) {
        _status.update { it.copy(listenerConnected = connected) }
        publish(context)
    }

    internal fun onPosted(context: Context, sbn: StatusBarNotification, ranking: NotificationListenerService.RankingMap?) {
        val n = toIncoming(context, sbn, ranking) ?: return
        if (!n.isOngoing && n.packageName != context.packageName) recentApps[n.packageName] = n.appLabel
        val ctx = readerContext(context)
        val outcome = engine.onPosted(n, settings, ctx)
        outcome.speech?.let { Speaker.enqueue(context, it, Speaker.Kind.NOTIFICATION, id = "notif:${n.key}") }
        val heard = outcome.heard
        val suggestion = outcome.suggestion
        if (heard != null && suggestion != null && settings.captureSuggestions) {
            scope.launch {
                val saved = runCatching {
                    val app = context.applicationContext as? MineApp ?: return@runCatching false
                    val storage = app.sharedStorage.await()
                    NotificationSuggestionWriter(storage) { System.currentTimeMillis() }.save(suggestion) != null
                }.getOrDefault(false)
                if (saved) {
                    engine.feed.update(heard.id) { it.copy(suggestionSaved = true) }
                    publish(context)
                }
            }
        }
        if (outcome.heard != null && !outcome.heard!!.spoken) scheduleCatchUp(context, 61_000)
        publish(context)
    }

    fun clearFeed(context: Context) {
        engine.feed.clear()
        publish(context)
    }

    /** Speaks a line through the same queue — the Settings "Try it" button. */
    fun speakSample(context: Context) {
        val line = when (settings.contentLevel) {
            com.anchor.core.notifications.ContentLevel.FULL -> "Riya on WhatsApp: running ten minutes late, start without me."
            com.anchor.core.notifications.ContentLevel.SENDER_ONLY -> "Message from Riya on WhatsApp."
            com.anchor.core.notifications.ContentLevel.APP_ONLY -> "New WhatsApp message."
        }
        Speaker.enqueue(context, line, Speaker.Kind.NOTIFICATION, id = "notif:sample")
    }

    // ---------------------------------------------------------------------------------------

    private fun scheduleCatchUp(context: Context, afterMs: Long) {
        if (engine.pendingCatchUpCount() == 0) return
        catchUpJob?.cancel()
        catchUpJob = scope.launch {
            delay(afterMs)
            val line = engine.takeCatchUp(settings, readerContext(context))
            if (line != null) Speaker.enqueue(context, line, Speaker.Kind.NOTIFICATION, id = "notif:catchup")
            publish(context)
            if (engine.pendingCatchUpCount() > 0) scheduleCatchUp(context, 30_000)
        }
    }

    private fun publish(context: Context) {
        _status.update {
            it.copy(
                accessGranted = hasAccess(context),
                settings = settings,
                feed = engine.feed.snapshot(),
                pendingCatchUp = engine.pendingCatchUpCount(),
                recentApps = recentApps.toMap(),
            )
        }
    }

    private fun readerContext(context: Context): ReaderContext {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val nm = context.getSystemService(NotificationManager::class.java)
        val dnd = nm?.currentInterruptionFilter?.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN } ?: false
        return ReaderContext(
            nowMs = now,
            ambientActive = AmbientStatusBus.isActive,
            ownPackage = context.packageName,
            inCall = isInCall(context),
            conversationBusy = conversationBusy || AudioRecorderHelper.isForegroundMicActive,
            headphonesConnected = headphonesConnected(context),
            minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE),
            dayStartMinute = dayStartMinute,
            dayEndMinute = dayEndMinute,
            hideContent = hideContent,
            dndActive = dnd,
        )
    }

    private fun isInCall(context: Context): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val mode = audio?.mode
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_RINGTONE) return true
        return try {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.callState?.let { it != TelephonyManager.CALL_STATE_IDLE } ?: false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun headphonesConnected(context: Context): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val headTypes = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            if (Build.VERSION.SDK_INT >= 31) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
        }
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headTypes }
    }

    private fun toIncoming(context: Context, sbn: StatusBarNotification, ranking: NotificationListenerService.RankingMap?): IncomingNotification? {
        val n = sbn.notification ?: return null
        val extras = n.extras ?: return null
        val pkg = sbn.packageName ?: return null

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val isGroupFlag = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)

        // MessagingStyle: read the newest message and its sender, not the whole thread.
        val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
        val last = style?.messages?.lastOrNull()
        val newest: String? = last?.text?.toString()
        val sender: String? = last?.person?.name?.toString()

        var importance: Int? = null
        var passesDnd: Boolean? = null
        if (ranking != null) {
            val r = NotificationListenerService.Ranking()
            if (ranking.getRanking(sbn.key, r)) {
                importance = r.importance
                passesDnd = r.matchesInterruptionFilter()
            }
        }

        return IncomingNotification(
            key = sbn.key,
            packageName = pkg,
            appLabel = label(context, pkg),
            title = title,
            text = newest ?: text,
            bigText = if (newest != null) null else big,
            category = n.category,
            postedAt = sbn.postTime,
            isOngoing = sbn.isOngoing || (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0,
            isGroupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            sender = sender,
            conversationTitle = style?.conversationTitle?.toString() ?: conversationTitle,
            isGroupConversation = style?.isGroupConversation ?: isGroupFlag,
            importance = importance,
            isSilentUpdate = false, // reposts are caught by key + content dedupe in triage
            passesDnd = passesDnd,
        )
    }

    private fun label(context: Context, pkg: String): String = labelCache.getOrPut(pkg) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
        }.getOrDefault(pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() })
    }

    // ---------------------------------------------------------------------------------------
    // Access
    // ---------------------------------------------------------------------------------------

    fun hasAccess(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return runCatching { nm.isNotificationListenerAccessGranted(ComponentName(context, MineNotificationListener::class.java)) }
            .getOrElse {
                val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
                flat.contains(context.packageName)
            }
    }

    /** Opens the system page where the user grants notification access to Mine specifically. */
    fun accessSettingsIntent(context: Context): Intent {
        val component = ComponentName(context, MineNotificationListener::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
