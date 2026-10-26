// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.relay

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * One outbound WebSocket to the relay, reconnected with exponential backoff until [start]'s job is
 * cancelled. Each socket opens with hello; calls run on [scope] and answer on the socket they came from.
 */
class RelayClient(
    private val http: OkHttpClient,
    private val url: String,
    /** APP_KEY, sent only as the Authorization header. */
    private val key: String,
    private val appVersion: String,
    private val tools: RelayTools,
    private val scope: CoroutineScope,
    private val backoff: Backoff = Backoff(),
    private val pingMs: Long = RelayProtocol.PING_MS,
    private val silenceMs: Long = RelayProtocol.SILENCE_MS,
    private val onState: (State) -> Unit = {},
) {
    enum class State { CONNECTING, CONNECTED, WAITING }

    /**
     * The contract asks for exponential backoff only; 1 s doubling to a 60 s cap is this app's choice
     * (unverified against the relay), kept below the relay's 90 s silence limit.
     */
    class Backoff(private val firstMs: Long = 1_000, private val maxMs: Long = 60_000) {
        fun delayMs(attempt: Int): Long = (firstMs shl attempt.coerceAtMost(20)).coerceAtMost(maxMs)
    }

    fun start(): Job = scope.launch {
        var attempt = 0
        while (isActive) {
            onState(State.CONNECTING)
            if (session()) attempt = 0
            onState(State.WAITING)
            delay(backoff.delayMs(attempt++))
        }
    }

    private sealed interface Event {
        data object Open : Event
        data class Text(val text: String) : Event
        data object Closed : Event
    }

    /** One socket until it closes, fails or goes silent; true when it opened (a reconnect starts over at 1 s). */
    private suspend fun session(): Boolean {
        val events = Channel<Event>(Channel.UNLIMITED)
        val socket = http.newWebSocket(Request.Builder().url(url).header("Authorization", RelayProtocol.authorization(key)).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { events.trySend(Event.Open) }
            override fun onMessage(webSocket: WebSocket, text: String) { events.trySend(Event.Text(text)) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); events.trySend(Event.Closed) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { events.trySend(Event.Closed) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { events.trySend(Event.Closed) }
        })
        var opened = false
        fun now() = System.nanoTime() / 1_000_000
        var heard = now(); var nextPing = heard + pingMs
        try {
            while (true) {
                val wait = if (opened) minOf(nextPing, heard + silenceMs) - now() else silenceMs
                when (val e = withTimeoutOrNull(wait.coerceAtLeast(1)) { events.receive() }) {
                    null -> {
                        if (!opened || now() - heard >= silenceMs) return opened
                        if (now() >= nextPing) { socket.send(RelayProtocol.PING); nextPing = now() + pingMs }
                    }
                    Event.Open -> {
                        opened = true; heard = now(); nextPing = heard + pingMs
                        socket.send(RelayProtocol.hello(appVersion, tools.definitions)); onState(State.CONNECTED)
                    }
                    is Event.Text -> {
                        heard = now()
                        scope.launch { RelayProtocol.respond(e.text, tools)?.let(socket::send) }
                    }
                    Event.Closed -> return opened
                }
            }
        } finally {
            socket.cancel()
        }
    }
}
