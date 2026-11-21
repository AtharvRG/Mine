// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Mine contributors
// Part of Mine. See LICENSE in the repository root.

package com.anchor.mine.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.anchor.core.widget.WidgetDataSource
import com.anchor.mine.MainActivity
import com.anchor.mine.MineApp
import com.anchor.mine.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AppWidgetProvider for home screen priority tasks and alarm counts (P1.1-05).
 */
class MineAppWidgetProvider : AppWidgetProvider() {

    companion object {
        fun updateWidgets(context: Context) {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val ids = appWidgetManager.getAppWidgetIds(
                    android.content.ComponentName(context, MineAppWidgetProvider::class.java)
                )
                if (ids != null && ids.isNotEmpty()) {
                    val intent = Intent(context, MineAppWidgetProvider::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    }
                    context.sendBroadcast(intent)
                }
            } catch (e: Exception) {
                android.util.Log.w("MineAppWidgetProvider", "Failed to broadcast widget update: ${e.message}")
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = try { goAsync() } catch (_: Exception) { null }
        val app = context.applicationContext as? MineApp
        if (app == null) {
            pendingResult?.finish()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val storage = app.ensureStorageInitialized().await()
                val dataSource = WidgetDataSource(storage) { System.currentTimeMillis() }
                val snapshot = dataSource.getSnapshot(limit = 3)

                for (appWidgetId in appWidgetIds) {
                    val views = RemoteViews(context.packageName, R.layout.widget_mine_tasks)

                    // Alarms count badge
                    views.setTextViewText(R.id.widget_alarms_count, "${snapshot.pendingAlarmsCount} alarms")

                    // Tasks
                    val tasks = snapshot.priorityTasks
                    if (tasks.isNotEmpty()) {
                        views.setTextViewText(R.id.widget_task_1, "• ${tasks[0].title}")
                        views.setViewVisibility(R.id.widget_task_1, View.VISIBLE)
                    } else {
                        views.setTextViewText(R.id.widget_task_1, "No pending priority tasks")
                        views.setViewVisibility(R.id.widget_task_1, View.VISIBLE)
                    }

                    if (tasks.size > 1) {
                        views.setTextViewText(R.id.widget_task_2, "• ${tasks[1].title}")
                        views.setViewVisibility(R.id.widget_task_2, View.VISIBLE)
                    } else {
                        views.setViewVisibility(R.id.widget_task_2, View.GONE)
                    }

                    if (tasks.size > 2) {
                        views.setTextViewText(R.id.widget_task_3, "• ${tasks[2].title}")
                        views.setViewVisibility(R.id.widget_task_3, View.VISIBLE)
                    } else {
                        views.setViewVisibility(R.id.widget_task_3, View.GONE)
                    }

                    // Click opens MainActivity
                    val intent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val pendingIntent = PendingIntent.getActivity(
                        context,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            } catch (_: Throwable) {
                // Ignore widget background update failure
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
