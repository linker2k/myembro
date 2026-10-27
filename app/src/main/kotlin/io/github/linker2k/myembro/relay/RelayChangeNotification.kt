// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.linker2k.myembro.MainActivity
import io.github.linker2k.myembro.R
import io.github.linker2k.myembro.ui.editor.AiWorkspace

/**
 * "The AI changed your design" while the editor is not on screen: one notification, updated in place by
 * later changes (with their count), so a run of edits in one conversation is one entry. A tap opens the
 * editor on the AI work project (AiWorkspace.ACTION_OPEN); showing the editor cancels it.
 */
object RelayChangeNotification {
    private const val CHANNEL = "relay_changes"
    private const val ID = 7103

    fun show(context: Context, headline: String, changes: Int, action:String=AiWorkspace.ACTION_OPEN, body:String?=null) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 26) app.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.relay_change_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(app, MainActivity::class.java).setAction(action)
        val tap = PendingIntent.getActivity(app, ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = body ?: if (changes > 1) app.getString(R.string.relay_change_tap_count, changes) else app.getString(R.string.relay_change_tap)
        val notification = NotificationCompat.Builder(app, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(headline).setContentText(text).setNumber(changes)
            .setOnlyAlertOnce(false).setCategory(NotificationCompat.CATEGORY_STATUS)
            // Its own group: otherwise Android bundles it with the "AI connection" notification, and a tap on the
            // bundle opens myembro's home instead of the editor (5558, 2026-10-26).
            .setGroup(CHANNEL).setContentIntent(tap).setAutoCancel(true).build()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(app).notify(ID, notification)
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context.applicationContext).cancel(ID)
}
