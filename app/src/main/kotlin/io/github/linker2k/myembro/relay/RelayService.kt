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
    private var relay: RelayClient? = null
    /** Network back or screen on: reconnect (or check the socket) now rather than after the backoff wait. */
    private val network = object : android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) { relay?.kick() }
    }
    private val screen = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { relay?.kick() }
    }
    private var watching = false

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
            .setContentIntent(android.app.PendingIntent.getActivity(this, NOTIFICATION,
                Intent(this, io.github.linker2k.myembro.MainActivity::class.java).setAction(io.github.linker2k.myembro.ui.editor.AiWorkspace.ACTION_OPEN),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).build()
        ServiceCompat.startForeground(this, NOTIFICATION, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        client?.cancel()
        // The AI work project (AiWorkspace), open on screen or not; it reads and changes itself on the main thread.
        val app = application
        val tools = RelayToolbox({ io.github.linker2k.myembro.ui.editor.AiWorkspace.relayDocument(app) })
        relay = RelayClient(OkHttpClient(), url, config.key, BuildConfig.VERSION_NAME, tools, scope, onState = { state.value = it })
        client = relay?.start()
        if (!watching) {
            watching = true
            getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(network)
            // Screen on and unlock are system broadcasts; registered at run time, not exported.
            ContextCompat.registerReceiver(this, screen, android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (watching) {
            runCatching { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(network) }
            runCatching { unregisterReceiver(screen) }
        }
        scope.cancel(); state.value = null; super.onDestroy()
    }

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
