//! Action dispatcher.
//!
//! Every handler is synchronous (enigo / std::process::Command are blocking and
//! `Enigo` is not `Send`), so `dispatch` is meant to be run inside
//! `tokio::task::spawn_blocking`. Each handler returns `Result<String, String>`
//! so the caller can turn it into an `ack` or `error` frame.

use std::process::Command as Proc;

use enigo::{Axis, Button, Coordinate, Direction, Enigo, Key, Keyboard, Mouse, Settings};

use crate::protocol::Args;

// --- Windows virtual-key codes for media / volume keys ---------------------
// We feed these through enigo's `Key::Other(vk)`, which maps straight onto a
// Windows VIRTUAL_KEY, matching the "via virtual key codes" requirement.
const VK_VOLUME_MUTE: u32 = 0xAD;
const VK_VOLUME_DOWN: u32 = 0xAE;
const VK_VOLUME_UP: u32 = 0xAF;
const VK_MEDIA_NEXT: u32 = 0xB0;
const VK_MEDIA_PREV: u32 = 0xB1;
const VK_MEDIA_PLAY_PAUSE: u32 = 0xB3;

/// Route an incoming action to its handler.
pub fn dispatch(action: &str, args: &Args) -> Result<String, String> {
    match action {
        "ping" => Ok("pong".into()),

        "volume_up" => tap_vk(VK_VOLUME_UP, "volume up"),
        "volume_down" => tap_vk(VK_VOLUME_DOWN, "volume down"),
        "mute" => tap_vk(VK_VOLUME_MUTE, "mute toggled"),

        "media_play_pause" => tap_vk(VK_MEDIA_PLAY_PAUSE, "play/pause"),
        "media_next" => tap_vk(VK_MEDIA_NEXT, "next track"),
        "media_prev" => tap_vk(VK_MEDIA_PREV, "previous track"),

        "launch" => {
            let app = args
                .app
                .as_deref()
                .ok_or("launch: missing 'app' argument")?;
            launch(app)
        }

        "type_text" => {
            let text = args
                .text
                .as_deref()
                .ok_or("type_text: missing 'text' argument")?;
            type_text(text)
        }

        "key" => {
            let combo = args
                .combo
                .as_deref()
                .ok_or("key: missing 'combo' argument")?;
            press_combo(combo)
        }

        "scroll" => {
            let dir = args
                .direction
                .as_deref()
                .ok_or("scroll: missing 'direction' argument")?;
            let amount = args.amount.unwrap_or(3);
            scroll(dir, amount)
        }

        "lock" => lock(),

        "mouse_move_rel" => {
            let x = args.x.ok_or("mouse_move_rel: missing 'x'")?;
            let y = args.y.ok_or("mouse_move_rel: missing 'y'")?;
            mouse_move(x, y, Coordinate::Rel)
        }
        "mouse_move_abs" => {
            let x = args.x.ok_or("mouse_move_abs: missing 'x'")?;
            let y = args.y.ok_or("mouse_move_abs: missing 'y'")?;
            mouse_move(x, y, Coordinate::Abs)
        }
        "mouse_click" => mouse_btn(args.button.as_deref().unwrap_or("left"), Direction::Click),
        "mouse_double" => {
            mouse_btn(args.button.as_deref().unwrap_or("left"), Direction::Click)?;
            mouse_btn(args.button.as_deref().unwrap_or("left"), Direction::Click)
        }
        "mouse_down" => mouse_btn(args.button.as_deref().unwrap_or("left"), Direction::Press),
        "mouse_up" => mouse_btn(args.button.as_deref().unwrap_or("left"), Direction::Release),
        "mouse_scroll" => mouse_scroll_xy(args.x.unwrap_or(0), args.y.unwrap_or(0)),

        "llm_query" => {
            let query = args
                .query
                .as_deref()
                .ok_or("llm_query: missing 'query' argument")?;
            llm_query(query)
        }

        "gamepad_state" => {
            #[cfg(windows)]
            {
                let lx = args.lx.unwrap_or(0).clamp(-32767, 32767) as i16;
                let ly = args.ly.unwrap_or(0).clamp(-32767, 32767) as i16;
                let rx = args.rx.unwrap_or(0).clamp(-32767, 32767) as i16;
                let ry = args.ry.unwrap_or(0).clamp(-32767, 32767) as i16;
                let lt = args.lt.unwrap_or(0).clamp(0, 255) as u8;
                let rt = args.rt.unwrap_or(0).clamp(0, 255) as u8;
                let btns = args.buttons.unwrap_or(0) as u16;
                gamepad_state(lx, ly, rx, ry, lt, rt, btns)
            }
            #[cfg(not(windows))]
            Err("gamepad_state: Windows only".into())
        }

        other => Err(format!("unknown action '{other}'")),
    }
}

/// Construct a fresh Enigo handle (cheap enough per-command).
fn enigo() -> Result<Enigo, String> {
    Enigo::new(&Settings::default()).map_err(|e| format!("enigo init failed: {e}"))
}

/// Click a raw virtual-key once (used for media + volume keys).
fn tap_vk(vk: u32, label: &str) -> Result<String, String> {
    let mut e = enigo()?;
    e.key(Key::Other(vk), Direction::Click)
        .map_err(|err| format!("{label} failed: {err}"))?;
    Ok(label.to_string())
}

/// Type literal text as keystrokes.
fn type_text(text: &str) -> Result<String, String> {
    let mut e = enigo()?;
    e.text(text).map_err(|err| format!("type failed: {err}"))?;
    Ok(format!("typed {} chars", text.chars().count()))
}

/// Scroll vertically. enigo treats positive lengths as "down", negative as "up".
fn scroll(dir: &str, amount: i32) -> Result<String, String> {
    let steps = amount.abs().max(1);
    let length = match dir.to_lowercase().as_str() {
        "up" => -steps,
        "down" => steps,
        other => return Err(format!("scroll: bad direction '{other}'")),
    };
    let mut e = enigo()?;
    e.scroll(length, Axis::Vertical)
        .map_err(|err| format!("scroll failed: {err}"))?;
    Ok(format!("scrolled {dir} {steps}"))
}

/// Press a '+'-separated combo: any number of modifiers plus one main key.
fn press_combo(combo: &str) -> Result<String, String> {
    let parts: Vec<String> = combo
        .split('+')
        .map(|p| p.trim().to_lowercase())
        .filter(|p| !p.is_empty())
        .collect();
    if parts.is_empty() {
        return Err("key: empty combo".into());
    }

    let mut modifiers: Vec<Key> = Vec::new();
    let mut main: Option<Key> = None;
    for p in &parts {
        match p.as_str() {
            "ctrl" | "control" => modifiers.push(Key::Control),
            "alt" => modifiers.push(Key::Alt),
            "shift" => modifiers.push(Key::Shift),
            "win" | "super" | "meta" | "cmd" => modifiers.push(Key::Meta),
            _ => main = Some(key_from_name(p)?),
        }
    }
    let main = main.ok_or("key: combo has no main (non-modifier) key")?;

    let mut e = enigo()?;
    // Hold modifiers, click the key, then always release the modifiers.
    for m in &modifiers {
        e.key(*m, Direction::Press)
            .map_err(|err| format!("key failed: {err}"))?;
    }
    let click = e.key(main, Direction::Click);
    for m in modifiers.iter().rev() {
        let _ = e.key(*m, Direction::Release);
    }
    click.map_err(|err| format!("key failed: {err}"))?;

    Ok(format!("pressed {combo}"))
}

/// Map a key name (or single character) to an enigo `Key`.
fn key_from_name(name: &str) -> Result<Key, String> {
    let k = match name {
        "esc" | "escape" => Key::Escape,
        "enter" | "return" => Key::Return,
        "tab" => Key::Tab,
        "space" => Key::Space,
        "backspace" => Key::Backspace,
        "delete" | "del" => Key::Delete,
        "insert" | "ins" => Key::Insert,
        "home" => Key::Home,
        "end" => Key::End,
        "pageup" | "pgup" => Key::PageUp,
        "pagedown" | "pgdn" => Key::PageDown,
        "up" => Key::UpArrow,
        "down" => Key::DownArrow,
        "left" => Key::LeftArrow,
        "right" => Key::RightArrow,
        "f1" => Key::F1,
        "f2" => Key::F2,
        "f3" => Key::F3,
        "f4" => Key::F4,
        "f5" => Key::F5,
        "f6" => Key::F6,
        "f7" => Key::F7,
        "f8" => Key::F8,
        "f9" => Key::F9,
        "f10" => Key::F10,
        "f11" => Key::F11,
        "f12" => Key::F12,
        _ => {
            // Fall back to a single-character key, e.g. "c" in "ctrl+c".
            let mut chars = name.chars();
            match (chars.next(), chars.next()) {
                (Some(c), None) => Key::Unicode(c),
                _ => return Err(format!("key: unknown key name '{name}'")),
            }
        }
    };
    Ok(k)
}

/// Launch a known alias or a raw command/path.
fn launch(app: &str) -> Result<String, String> {
    // (program, extra args). Aliases are resolved case-insensitively.
    let (program, args): (&str, Vec<&str>) = match app.to_lowercase().as_str() {
        "notepad" => ("notepad", vec![]),
        "calculator" | "calc" => ("calc", vec![]),
        "explorer" | "files" => ("explorer", vec![]),
        // `explorer <url>` opens the user's default browser.
        "browser" => ("explorer", vec!["https://www.google.com"]),
        // Windows Terminal; falls back to an error if not installed.
        "terminal" => ("wt", vec![]),
        // Not a known alias: treat the whole string as a raw program/path.
        _ => (app, vec![]),
    };

    Proc::new(program)
        .args(&args)
        .spawn()
        .map_err(|e| format!("launch: failed to start '{app}': {e}"))?;
    Ok(format!("launched {app}"))
}

/// Lock the Windows session via the classic rundll32 entry point.
fn lock() -> Result<String, String> {
    Proc::new("rundll32")
        .arg("user32.dll,LockWorkStation")
        .spawn()
        .map_err(|e| format!("lock: failed: {e}"))?;
    Ok("locked".into())
}

fn mouse_move(x: i32, y: i32, coord: Coordinate) -> Result<String, String> {
    let mut e = enigo()?;
    e.move_mouse(x, y, coord)
        .map_err(|err| format!("mouse_move failed: {err}"))?;
    Ok(format!("mouse moved {x},{y}"))
}

fn mouse_btn(btn: &str, dir: Direction) -> Result<String, String> {
    let button = match btn {
        "right" => Button::Right,
        "middle" => Button::Middle,
        _ => Button::Left,
    };
    let mut e = enigo()?;
    e.button(button, dir)
        .map_err(|err| format!("mouse_btn failed: {err}"))?;
    Ok(format!("mouse {btn} {dir:?}"))
}

fn mouse_scroll_xy(x: i32, y: i32) -> Result<String, String> {
    let mut e = enigo()?;
    if y != 0 {
        e.scroll(y, Axis::Vertical)
            .map_err(|err| format!("scroll_y failed: {err}"))?;
    }
    if x != 0 {
        e.scroll(x, Axis::Horizontal)
            .map_err(|err| format!("scroll_x failed: {err}"))?;
    }
    Ok(format!("scrolled x={x} y={y}"))
}

// System prompt sent with every Ollama request. Concise to fit small-context models.
const LLM_SYSTEM: &str = "\
You are a Windows PC voice assistant. Convert the user's natural language into a JSON \
action array. Available actions:\n\
  volume_up, volume_down, mute\n\
  media_play_pause, media_next, media_prev\n\
  launch        {\"app\": \"notepad\"|\"calculator\"|\"browser\"|\"terminal\"|\"explorer\"}\n\
  type_text     {\"text\": \"...\"}\n\
  key           {\"combo\": \"ctrl+c\"|\"win+d\"|\"alt+f4\"|...}\n\
  scroll        {\"direction\": \"up\"|\"down\", \"amount\": 3}\n\
  lock\n\
Respond ONLY with a valid JSON array of action objects. No explanation, no markdown fences.\n\
Example: [{\"action\":\"launch\",\"args\":{\"app\":\"notepad\"}},{\"action\":\"type_text\",\"args\":{\"text\":\"hello\"}}]";

fn llm_query(query: &str) -> Result<String, String> {
    let url = std::env::var("VOXWIN_OLLAMA_URL")
        .unwrap_or_else(|_| "http://localhost:11434/api/generate".to_string());
    let model = std::env::var("VOXWIN_OLLAMA_MODEL").unwrap_or_else(|_| "llama3.2".to_string());

    let body = serde_json::json!({
        "model": model,
        "system": LLM_SYSTEM,
        "prompt": query,
        "stream": false,
    });

    let resp: serde_json::Value = ureq::post(&url)
        .timeout(std::time::Duration::from_secs(30))
        .send_json(body)
        .map_err(|e| format!("llm_query: Ollama request failed: {e}"))?
        .into_json()
        .map_err(|e| format!("llm_query: bad JSON from Ollama: {e}"))?;

    let raw = resp["response"]
        .as_str()
        .ok_or("llm_query: missing 'response' field in Ollama reply")?;

    // Strip optional markdown code fences the model might add.
    let clean = raw
        .trim()
        .trim_start_matches("```json")
        .trim_start_matches("```")
        .trim_end_matches("```")
        .trim();

    let actions: Vec<serde_json::Value> = serde_json::from_str(clean)
        .map_err(|e| format!("llm_query: could not parse action list: {e}\nModel said: {clean}"))?;

    if actions.is_empty() {
        return Ok("AI: no actions".to_string());
    }

    let mut results: Vec<String> = Vec::new();
    for item in &actions {
        let action = item["action"].as_str().unwrap_or("unknown");
        // Guard against infinite recursion.
        if action == "llm_query" {
            results.push("skipped nested llm_query".to_string());
            continue;
        }
        let raw_args = item
            .get("args")
            .cloned()
            .unwrap_or(serde_json::Value::Object(Default::default()));
        let args: crate::protocol::Args = serde_json::from_value(raw_args).unwrap_or_default();
        match dispatch(action, &args) {
            Ok(msg) => results.push(msg),
            Err(e) => results.push(format!("{action}: {e}")),
        }
    }

    Ok(format!("AI: {}", results.join(", ")))
}

// ── Feature 3: virtual Xbox 360 gamepad via ViGEmBus ─────────────────────────

#[cfg(windows)]
struct GamepadSession(vigem_client::Xbox360Wired<vigem_client::Client>);

// SAFETY: Xbox360Wired wraps a Windows HANDLE to the ViGEmBus device.
// The driver serialises concurrent IOCTLs, and we additionally gate all
// mutations through the Mutex, so sending the wrapper to another thread is safe.
#[cfg(windows)]
unsafe impl Send for GamepadSession {}

#[cfg(windows)]
static GAMEPAD: std::sync::LazyLock<std::sync::Mutex<Option<GamepadSession>>> =
    std::sync::LazyLock::new(|| std::sync::Mutex::new(None));

#[cfg(windows)]
fn gamepad_state(
    lx: i16,
    ly: i16,
    rx: i16,
    ry: i16,
    lt: u8,
    rt: u8,
    buttons: u16,
) -> Result<String, String> {
    use vigem_client::{Client, TargetId, XButtons, XGamepad, Xbox360Wired};

    let mut guard = GAMEPAD.lock().unwrap();
    if guard.is_none() {
        let client = Client::connect()
            .map_err(|e| format!("ViGEmBus not installed (install ViGEmBus driver): {e}"))?;
        let id = TargetId::XBOX360_WIRED;
        let mut target = Xbox360Wired::new(client, id);
        target
            .plugin()
            .map_err(|e| format!("gamepad plugin: {e}"))?;
        target
            .wait_ready()
            .map_err(|e| format!("gamepad wait_ready: {e}"))?;
        *guard = Some(GamepadSession(target));
    }
    let session = guard.as_mut().unwrap();
    let gamepad = XGamepad {
        buttons: XButtons { raw: buttons },
        left_trigger: lt,
        right_trigger: rt,
        thumb_lx: lx,
        thumb_ly: ly,
        thumb_rx: rx,
        thumb_ry: ry,
    };
    session
        .0
        .update(&gamepad)
        .map_err(|e| format!("gamepad update: {e}"))?;
    Ok("gamepad updated".into())
}
