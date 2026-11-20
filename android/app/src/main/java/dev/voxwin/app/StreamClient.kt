package dev.voxwin.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * Receives JPEG screen captures streamed from the daemon's port+1 WebSocket endpoint.
 *
 * Protocol:
 *   First frame (text): {"type":"stream_info","width":W,"height":H} — original PC resolution.
 *   Subsequent frames (binary): one JPEG image per frame (50% scale of the PC screen).
 */
class StreamClient {

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _frame = MutableStateFlow<ByteArray?>(null)
    val frame: StateFlow<ByteArray?> = _frame.asStateFlow()

    // The daemon's original PC screen dimensions (before 50% scale).
    var screenWidth: Int = 1920
        private set
    var screenHeight: Int = 1080
        private set

    private var ws: WebSocket? = null

    fun connect(host: String, port: Int) {
        if (_state.value == ConnectionState.CONNECTED ||
            _state.value == ConnectionState.CONNECTING
        ) return

        _state.value = ConnectionState.CONNECTING
        val request = Request.Builder().url("ws://$host:$port").build()
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = ConnectionState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = Json.parseToJsonElement(text).jsonObject
                    if (obj["type"]?.jsonPrimitive?.content == "stream_info") {
                        screenWidth  = obj["width"]?.jsonPrimitive?.int ?: 1920
                        screenHeight = obj["height"]?.jsonPrimitive?.int ?: 1080
                    }
                } catch (_: Exception) {}
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                _frame.value = bytes.toByteArray()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.value = ConnectionState.ERROR
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = ConnectionState.DISCONNECTED
            }
        })
    }

    fun disconnect() {
        ws?.close(1000, null)
        ws = null
        _state.value = ConnectionState.DISCONNECTED
        _frame.value = null
    }
}
