//! Wire protocol shared with the Android client.
//!
//! All traffic is JSON over a single WebSocket text channel on the LAN.
//! Client -> server frames are `Command`; server -> client frames are `Server`.

use serde::{Deserialize, Serialize};

/// A command sent by the Android app.
///
/// Example:
/// ```json
/// { "type": "command", "token": "voxwin-dev", "action": "volume_up", "args": {} }
/// ```
#[derive(Debug, Deserialize)]
pub struct Command {
    /// Always "command" for client frames (kept for forward-compatibility / validation).
    #[serde(rename = "type", default)]
    pub type_: String,
    /// Shared secret; must match the daemon's VOXWIN_TOKEN.
    #[serde(default)]
    pub token: String,
    /// Action name, e.g. "volume_up", "launch", "key".
    pub action: String,
    /// Per-action arguments; absent/empty for the simple actions.
    #[serde(default)]
    pub args: Args,
}

/// Union of every argument any action might carry. All fields are optional;
/// each handler reads only the ones it needs.
#[derive(Debug, Default, Clone, Deserialize)]
pub struct Args {
    /// "launch": app alias or raw command/path.
    pub app: Option<String>,
    /// "type_text": literal text to type.
    pub text: Option<String>,
    /// "key": '+'-separated combo, e.g. "ctrl+shift+esc".
    pub combo: Option<String>,
    /// "scroll": "up" | "down".
    pub direction: Option<String>,
    /// "scroll": number of scroll steps (defaults to 3 when omitted).
    pub amount: Option<i32>,
}

/// A frame the daemon sends back to the Android app.
#[derive(Debug, Serialize)]
#[serde(tag = "type")]
pub enum Server {
    /// Sent exactly once, right after the connection is established.
    #[serde(rename = "hello")]
    Hello {
        version: &'static str,
        /// Machine name, so the user can confirm they hit the right PC.
        host: String,
    },
    /// Sent after a command succeeds.
    #[serde(rename = "ack")]
    Ack {
        action: String,
        ok: bool, // always true
        #[serde(skip_serializing_if = "Option::is_none")]
        message: Option<String>,
    },
    /// Sent after a command fails (including "unauthorized").
    #[serde(rename = "error")]
    Error {
        action: String,
        ok: bool, // always false
        message: String,
    },
}

impl Server {
    /// Build the one-time greeting frame.
    pub fn hello(host: String) -> Self {
        Server::Hello {
            version: "1.0",
            host,
        }
    }

    /// Build a success reply for `action` with an optional human message.
    pub fn ack(action: impl Into<String>, message: Option<String>) -> Self {
        Server::Ack {
            action: action.into(),
            ok: true,
            message,
        }
    }

    /// Build a failure reply for `action`.
    pub fn error(action: impl Into<String>, message: impl Into<String>) -> Self {
        Server::Error {
            action: action.into(),
            ok: false,
            message: message.into(),
        }
    }
}
