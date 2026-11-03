package dev.voxwin.app

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** High-level connection state surfaced to the UI. */
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/**
 * OkHttp-backed WebSocket client for the voxwin daemon.
 *
 * - [connect] opens a ws:// connection to host:port.
 * - [send] serializes a [Command] (injecting the shared token) and transmits it.
 * - [state] exposes the live connection state.
 * - [messages] emits parsed [ServerMessage]s (hello / ack / error) as they arrive.
 *
 * Auto-reconnect is intentionally left out (optional per spec); the UI drives
 * reconnection by calling [connect] again.
 */
class VoxwinClient(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // keep the socket open
        .build()

    private var webSocket: WebSocket? = null
    private var token: String = ""

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<ServerMessage>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<ServerMessage> = _messages.asSharedFlow()

    /** Optional lightweight listener alternative to the flow, for non-coroutine callers. */
    var onMessage: ((ServerMessage) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    fun connect(host: String, port: Int, token: String) {
        disconnect()
        this.token = token
        _state.value = ConnectionState.CONNECTING

        val url = "ws://$host:$port/"
        val request = Request.Builder().url(url).build()
        log("Connecting to $url")
        webSocket = http.newWebSocket(request, listener)
    }

    fun disconnect() {
        webSocket?.close(NORMAL_CLOSURE, "client closing")
        webSocket = null
        if (_state.value != ConnectionState.DISCONNECTED) {
            _state.value = ConnectionState.DISCONNECTED
        }
    }

    /** Send a command. Returns false if not currently connected. */
    fun send(command: Command): Boolean {
        val ws = webSocket
        if (ws == null || _state.value != ConnectionState.CONNECTED) {
            log("Not connected; dropping '${command.action}'")
            return false
        }
        val payload = json.encodeToString(WireCommand.serializer(), command.toWireMessage(token))
        val ok = ws.send(payload)
        if (ok) log("-> $payload") else log("Failed to enqueue '${command.action}'")
        return ok
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _state.value = ConnectionState.CONNECTED
            log("Connected")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            log("<- $text")
            val msg = runCatching {
                json.decodeFromString(ServerMessage.serializer(), text)
            }.getOrNull()
            if (msg == null) {
                log("Unparseable message")
                return
            }
            _messages.tryEmit(msg)
            onMessage?.invoke(msg)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _state.value = ConnectionState.DISCONNECTED
            log("Closed ($code) $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _state.value = ConnectionState.ERROR
            log("Error: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun log(message: String) {
        onLog?.invoke(message)
    }

    companion object {
        private const val NORMAL_CLOSURE = 1000
    }
}
