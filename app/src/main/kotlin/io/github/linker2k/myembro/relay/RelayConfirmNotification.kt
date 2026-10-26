// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.linker2k.myembro.R

/**
 * Heads-up notification while a relay call waits for the user's answer on the phone (remove_objects):
 * the user is usually in the chatbot app, so a tap brings myembro back, where the dialog is waiting.
 * Shown only if the user allowed notifications (asked when the AI connection is turned on).
 */
object RelayConfirmNotification {
    private const val CHANNEL = "relay_confirm"
    private const val ID = 7102

    fun show(context: Context, names: List<String>) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 26) app.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.relay_confirm_channel), NotificationManager.IMPORTANCE_HIGH))
        // The launcher intent brings the running task forward as it is, with the editor and its dialog.
        val open = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        val tap = PendingIntent.getActivity(app, ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(app, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(app.getString(R.string.relay_remove_title))
            .setContentText(app.getString(R.string.relay_confirm_notification, names.joinToString(", ")))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(tap).setAutoCancel(true).setTimeoutAfter(90_000).build()
        runCatching { NotificationManagerCompat.from(app).notify(ID, notification) } // SecurityException without permission
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context.applicationContext).cancel(ID)
}
