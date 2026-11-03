//! Action dispatcher.
//!
//! Every handler is synchronous (enigo / std::process::Command are blocking and
//! `Enigo` is not `Send`), so `dispatch` is meant to be run inside
//! `tokio::task::spawn_blocking`. Each handler returns `Result<String, String>`
//! so the caller can turn it into an `ack` or `error` frame.

use std::process::Command as Proc;

use enigo::{Axis, Direction, Enigo, Key, Keyboard, Mouse, Settings};

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
