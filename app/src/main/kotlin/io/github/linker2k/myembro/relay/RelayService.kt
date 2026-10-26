// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.linker2k.myembro.BuildConfig
import io.github.linker2k.myembro.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

/** Keeps the relay WebSocket open while "AI connection" is on, as a foreground service with a notification. */
class RelayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: Job? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val config = RelayPreferences(this).read()
        val url = RelayProtocol.appUrl(config.address, config.key)
        if (!config.enabled || url == null) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.relay_title), NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.relay_title)).setContentText(getString(R.string.relay_notification))
            .setOngoing(true).build()
        ServiceCompat.startForeground(this, NOTIFICATION, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        client?.cancel()
        // The screens read and change their project on the main thread themselves (EditorViewModel, ViewerViewModel).
        val tools = RelayToolbox({ RelayProjectSource.current() })
        client = RelayClient(OkHttpClient(), url, BuildConfig.VERSION_NAME, tools, scope, onState = { state.value = it }).start()
        return START_STICKY
    }

    override fun onDestroy() { scope.cancel(); state.value = null; super.onDestroy() }

    companion object {
        private const val CHANNEL = "relay"
        private const val NOTIFICATION = 7101
        private val state = MutableStateFlow<RelayClient.State?>(null)
        /** Null while off. */
        val connection: StateFlow<RelayClient.State?> = state

        /** Starts or stops the service to match the saved setting (call from the foreground). */
        fun sync(context: Context) {
            val intent = Intent(context, RelayService::class.java)
            val config = RelayPreferences(context).read()
            if (config.enabled && RelayProtocol.appUrl(config.address, config.key) != null) ContextCompat.startForegroundService(context, intent)
            else context.stopService(intent)
        }
    }
}
