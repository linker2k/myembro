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
    /** After the app's ping, a socket that says nothing for this long is dead (a relay redeploy, a lost network). */
    private val pongTimeoutMs: Long = RelayProtocol.PONG_TIMEOUT_MS,
    private val onState: (State) -> Unit = {},
) {
    enum class State { CONNECTING, CONNECTED, WAITING }

    /**
     * The contract asks for exponential backoff only; 1 s doubling to a 10 s cap is this app's choice. The cap
     * was 60 s until a first call after a relay redeploy took 101 s on a phone (2026-10-26); [kick] skips the wait.
     */
    class Backoff(private val firstMs: Long = 1_000, private val maxMs: Long = 10_000) {
        fun delayMs(attempt: Int): Long = (firstMs shl attempt.coerceAtMost(20)).coerceAtMost(maxMs)
    }

    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * The network came back or the screen turned on: reconnect now instead of after the backoff wait, and
     * check an open socket with a ping that must be answered within [pongTimeoutMs].
     */
    fun kick() { wake.trySend(Unit) }

    fun start(): Job = scope.launch {
        var attempt = 0
        while (isActive) {
            onState(State.CONNECTING)
            if (session()) attempt = 0
            onState(State.WAITING)
            withTimeoutOrNull(backoff.delayMs(attempt++)) { wake.receive() }
        }
    }

    private sealed interface Event {
        data object Open : Event
        data class Text(val text: String) : Event
        data object Closed : Event
        data object Woken : Event
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
        /** Set when the app pinged; anything heard clears it. */
        var answerBy: Long? = null
        fun ping() { socket.send(RelayProtocol.PING); nextPing = now() + pingMs; if (answerBy == null) answerBy = now() + pongTimeoutMs }
        try {
            while (true) {
                val wait = if (opened) minOf(nextPing, heard + silenceMs, answerBy ?: Long.MAX_VALUE) - now() else silenceMs
                val e = withTimeoutOrNull(wait.coerceAtLeast(1)) {
                    kotlinx.coroutines.selects.select<Event> {
                        events.onReceive { it }
                        wake.onReceive { Event.Woken }
                    }
                }
                when (e) {
                    // Network back or screen on: check the open socket now (a socket still opening just goes on).
                    Event.Woken -> if (opened && answerBy == null) ping()
                    null -> {
                        if (!opened || now() - heard >= silenceMs) return opened
                        if (answerBy?.let { now() >= it } == true) return true
                        if (now() >= nextPing) ping()
                    }
                    Event.Open -> {
                        opened = true; heard = now(); nextPing = heard + pingMs; answerBy = null
                        socket.send(RelayProtocol.hello(appVersion, tools.definitions)); onState(State.CONNECTED)
                    }
                    is Event.Text -> {
                        heard = now(); answerBy = null
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
