package dev.voxwin.app

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.util.UUID
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** High-level connection state surfaced to the UI. */
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/**
 * Unified client for the voxwin daemon — supports three connection modes:
 *
 *  - [connect]          ws://host:port  — plain WebSocket over LAN
 *  - [connect] tls=true wss://host:port — TLS WebSocket; accepts self-signed certs
 *  - [connectBluetooth]                 — Bluetooth RFCOMM / SPP
 *
 * All three modes share the same JSON wire protocol and surface state / messages
 * through the same [state] and [messages] flows.
 */
class VoxwinClient(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    // ── Standard (plain) OkHttp client ──────────────────────────────────────
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // ── Trust-all OkHttp client (for wss:// with self-signed daemon cert) ───
    @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val trustAllHttp: OkHttpClient by lazy {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(5, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    // ── WebSocket connection (plain or TLS) ──────────────────────────────────
    private var webSocket: WebSocket? = null

    // ── Bluetooth connection ─────────────────────────────────────────────────
    private var btSocket: BluetoothSocket? = null
    private var btWriter: PrintWriter? = null
    private var btThread: Thread? = null

    private var token: String = ""

    // ── Public state ─────────────────────────────────────────────────────────
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<ServerMessage>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<ServerMessage> = _messages.asSharedFlow()

    var onMessage: ((ServerMessage) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    // ── WebSocket (plain or TLS) ─────────────────────────────────────────────

    /**
     * Open a WebSocket connection.
     * @param tls true → wss:// with a trust-all TLS client (for self-signed daemon certs)
     */
    fun connect(host: String, port: Int, token: String, tls: Boolean = false) {
        disconnect()
        this.token = token
        _state.value = ConnectionState.CONNECTING

        val scheme = if (tls) "wss" else "ws"
        val url = "$scheme://$host:$port/"
        val client = if (tls) trustAllHttp else http
        log("Connecting to $url")
        webSocket = client.newWebSocket(Request.Builder().url(url).build(), wsListener)
    }

    // ── Bluetooth RFCOMM ─────────────────────────────────────────────────────

    /** Standard SPP UUID — matches the one registered in the Windows daemon SDP record. */
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /**
     * Connect to [device] over Bluetooth RFCOMM.
     *
     * Uses SDP discovery via the SPP UUID first; falls back to channel 1 via
     * reflection if SDP fails (common on some OEM firmwares).
     */
    fun connectBluetooth(device: BluetoothDevice, token: String) {
        disconnect()
        this.token = token
        _state.value = ConnectionState.CONNECTING
        log("Connecting via Bluetooth to ${device.name ?: device.address}")

        btThread = Thread {
            try {
                val socket = try {
                    device.createRfcommSocketToServiceRecord(SPP_UUID)
                } catch (e: Exception) {
                    // Fallback: hidden API with channel 1 when SDP lookup fails.
                    @Suppress("PrivateApi")
                    val m = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
                    m.invoke(device, 1) as BluetoothSocket
                }
                socket.connect()
                btSocket = socket
                btWriter = PrintWriter(socket.outputStream.bufferedWriter(), true)
                _state.value = ConnectionState.CONNECTED
                log("Connected via Bluetooth to ${device.name ?: device.address}")

                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { processIncoming(it) }
                }
            } catch (e: InterruptedException) {
                // Normal shutdown via disconnect().
            } catch (e: Exception) {
                _state.value = ConnectionState.ERROR
                log("Bluetooth error: ${e.message ?: e.javaClass.simpleName}")
            }
        }.apply { isDaemon = true; start() }
    }

    // ── Unified send ─────────────────────────────────────────────────────────

    /** Send a command. Returns false if not connected. */
    fun send(command: Command): Boolean {
        if (_state.value != ConnectionState.CONNECTED) {
            log("Not connected; dropping '${command.action}'")
            return false
        }
        val payload = json.encodeToString(WireCommand.serializer(), command.toWireMessage(token))

        // WebSocket path
        val ws = webSocket
        if (ws != null) {
            val ok = ws.send(payload)
            if (ok) log("-> $payload") else log("Failed to enqueue '${command.action}'")
            return ok
        }

        // Bluetooth path
        val writer = btWriter
        if (writer != null) {
            return try {
                writer.println(payload)
                log("-> $payload")
                true
            } catch (e: Exception) {
                _state.value = ConnectionState.ERROR
                log("BT send failed: ${e.message}")
                false
            }
        }

        log("No active transport; dropping '${command.action}'")
        return false
    }

    /** Close whatever transport is active. */
    fun disconnect() {
        webSocket?.close(NORMAL_CLOSURE, "client closing")
        webSocket = null

        btThread?.interrupt()
        btThread = null
        try { btSocket?.close() } catch (_: Exception) {}
        btSocket = null
        btWriter = null

        if (_state.value != ConnectionState.DISCONNECTED) {
            _state.value = ConnectionState.DISCONNECTED
        }
    }

    // ── WebSocket listener ────────────────────────────────────────────────────

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _state.value = ConnectionState.CONNECTED
            log("Connected")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            processIncoming(text)
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

    // ── Shared incoming-message handler ──────────────────────────────────────

    private fun processIncoming(text: String) {
        log("<- $text")
        val msg = runCatching {
            json.decodeFromString(ServerMessage.serializer(), text)
        }.getOrNull() ?: run { log("Unparseable message"); return }
        _messages.tryEmit(msg)
        onMessage?.invoke(msg)
    }

    private fun log(message: String) { onLog?.invoke(message) }

    companion object {
        private const val NORMAL_CLOSURE = 1000
    }
}
