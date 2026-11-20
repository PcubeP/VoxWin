package dev.voxwin.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Which transport layer is active. */
enum class TransportMode { LAN, BLUETOOTH }

/**
 * Wire-protocol data models shared between the Android client and the Rust
 * Windows daemon. See the protocol spec: JSON over a single WebSocket text
 * channel, default port 8787.
 */

/** Action names understood by the daemon. Kept as constants to avoid typos. */
object Actions {
    const val PING = "ping"
    const val VOLUME_UP = "volume_up"
    const val VOLUME_DOWN = "volume_down"
    const val MUTE = "mute"
    const val MEDIA_PLAY_PAUSE = "media_play_pause"
    const val MEDIA_NEXT = "media_next"
    const val MEDIA_PREV = "media_prev"
    const val LAUNCH = "launch"
    const val TYPE_TEXT = "type_text"
    const val KEY = "key"
    const val SCROLL = "scroll"
    const val LOCK = "lock"
    // Touchpad / remote mouse
    const val MOUSE_MOVE_REL = "mouse_move_rel"
    const val MOUSE_MOVE_ABS = "mouse_move_abs"
    const val MOUSE_CLICK    = "mouse_click"
    const val MOUSE_DOUBLE   = "mouse_double"
    const val MOUSE_DOWN     = "mouse_down"
    const val MOUSE_UP       = "mouse_up"
    const val MOUSE_SCROLL   = "mouse_scroll"
    // AI / LLM
    const val LLM_QUERY = "llm_query"
    // Gamepad (Feature 3)
    const val GAMEPAD_STATE = "gamepad_state"
}

/** XInput gamepad button bitmask flags (XUSB_GAMEPAD_* constants). */
object GamepadButtons {
    const val DPAD_UP      = 0x0001
    const val DPAD_DOWN    = 0x0002
    const val DPAD_LEFT    = 0x0004
    const val DPAD_RIGHT   = 0x0008
    const val START        = 0x0010
    const val BACK         = 0x0020
    const val LEFT_THUMB   = 0x0040
    const val RIGHT_THUMB  = 0x0080
    const val LB           = 0x0100
    const val RB           = 0x0200
    const val A            = 0x1000
    const val B            = 0x2000
    const val X            = 0x4000
    const val Y            = 0x8000
}

/**
 * A command sent from the Android client to the daemon.
 *
 * Serializes to:
 * ```
 * { "type": "command", "token": "...", "action": "...", "args": { ... } }
 * ```
 * [args] is always present (empty object when the action takes no args) to
 * keep the wire shape uniform.
 */
@Serializable
data class Command(
    val action: String,
    val args: JsonObject = JsonObject(emptyMap()),
) {
    /** Builds the full outbound message, injecting the constant type + token. */
    fun toWireMessage(token: String): WireCommand =
        WireCommand(token = token, action = action, args = args)

    companion object {
        /** Convenience builder for an args-less command. */
        fun of(action: String): Command = Command(action)

        /** Convenience builder from a map of string args. */
        fun of(action: String, args: Map<String, Any>): Command {
            val obj = buildJsonObject {
                for ((k, v) in args) {
                    when (v) {
                        is Int -> put(k, JsonPrimitive(v))
                        is Boolean -> put(k, JsonPrimitive(v))
                        else -> put(k, JsonPrimitive(v.toString()))
                    }
                }
            }
            return Command(action, obj)
        }
    }
}

/** The exact object serialized onto the wire for an outbound command. */
@Serializable
data class WireCommand(
    val type: String = "command",
    val token: String,
    val action: String,
    val args: JsonObject = JsonObject(emptyMap()),
)

/**
 * Any inbound message from the daemon. The daemon tags each message with a
 * "type" discriminator: "hello", "ack", or "error". We deserialize leniently
 * into a single flat type so unknown/optional fields don't blow up parsing.
 */
@Serializable
data class ServerMessage(
    val type: String,
    // hello
    val version: String? = null,
    val host: String? = null,
    // ack / error
    val action: String? = null,
    val ok: Boolean? = null,
    val message: String? = null,
) {
    val isHello: Boolean get() = type == "hello"
    val isAck: Boolean get() = type == "ack"
    val isError: Boolean get() = type == "error"
}
