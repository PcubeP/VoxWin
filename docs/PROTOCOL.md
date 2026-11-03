# voxwin wire protocol

This is the **canonical** description of the voxwin wire protocol. The Android
client and the Rust daemon must both conform to what is written here.

## Transport

- A single **WebSocket** connection from the Android client to the Windows
  daemon.
- All frames are **text** frames containing one **JSON** object.
- Plain `ws://` over the LAN. Default port **8787**.

## Client -> server: commands

Every message the Android client sends to the daemon has this shape:

```json
{
  "type": "command",
  "token": "<shared secret string>",
  "action": "<action name>",
  "args": { }
}
```

- `type` is always `"command"`.
- `token` is the shared secret the user entered in the app. It is sent on every
  message.
- `action` names the action to perform (see below).
- `args` is an object with per-action parameters. It may be empty `{}` for
  actions that take no arguments.

### Supported actions

| action | args | effect |
|--------|------|--------|
| `ping` | `{}` | health check |
| `volume_up` | `{}` | raise system volume |
| `volume_down` | `{}` | lower system volume |
| `mute` | `{}` | toggle mute |
| `media_play_pause` | `{}` | media play/pause |
| `media_next` | `{}` | next track |
| `media_prev` | `{}` | previous track |
| `launch` | `{ "app": "notepad" }` | launch an app (see aliases) |
| `type_text` | `{ "text": "hello world" }` | type the literal text as keystrokes |
| `key` | `{ "combo": "ctrl+shift+esc" }` | press a key combo |
| `scroll` | `{ "direction": "up"\|"down", "amount": 3 }` | scroll |
| `lock` | `{}` | lock the Windows session |

#### `launch` aliases

The `app` value is matched (case-insensitively) against known aliases:

- `notepad`
- `calculator`
- `explorer`
- `browser`
- `terminal`

Any other value is treated as a **raw command / path** and launched as-is.

#### `key` combos

`combo` is a `'+'`-separated string. Modifiers `ctrl`, `alt`, `shift`, `win`
may be combined with a single key, e.g. `ctrl+c`, `ctrl+shift+esc`, `win+e`.

#### `scroll` args

- `direction`: `"up"` or `"down"`.
- `amount`: integer number of scroll steps (the client defaults to `3`).

## Server -> client: responses

### On connect

Immediately after the WebSocket opens, the server sends exactly one `hello`:

```json
{ "type": "hello", "version": "1.0", "host": "<machine name>" }
```

### After each command

On success:

```json
{ "type": "ack", "action": "<action>", "ok": true, "message": "optional human text" }
```

On failure:

```json
{ "type": "error", "action": "<action>", "ok": false, "message": "what went wrong" }
```

### Authorization failure

If `token` is missing or wrong, the server replies with an error and then
**closes** the connection:

```json
{ "type": "error", "action": "<action>", "ok": false, "message": "unauthorized" }
```

## Voice -> action mapping (client side)

The Android app transcribes speech on-device and maps the recognized phrase to
an action before sending. Matching is **case-insensitive** and the phrase is
**trimmed**.

| spoken phrase | action sent |
|---------------|-------------|
| "volume up" / "louder" | `volume_up` |
| "volume down" / "quieter" | `volume_down` |
| "mute" / "unmute" | `mute` |
| "play" / "pause" / "play pause" | `media_play_pause` |
| "next" / "next track" / "skip" | `media_next` |
| "previous" / "back" / "last track" | `media_prev` |
| "open X" / "launch X" / "start X" | `launch` with `{ "app": "X" }` |
| "type TEXT..." | `type_text` with `{ "text": "TEXT" }` |
| "press COMBO" (e.g. "press control c") | `key` with `{ "combo": <normalized> }` |
| "scroll up" / "scroll down" | `scroll` with `{ "direction": ..., "amount": 3 }` |
| "lock" / "lock computer" / "lock the computer" | `lock` |
| unrecognized | show "didn't catch that"; send **nothing** |

### `press` normalization

For a spoken combo like "press control c":

- drop the leading word "press"
- lower-case
- replace spaces with `+`
- rewrite the word `control` to `ctrl`

So "press control c" becomes `{ "combo": "ctrl+c" }`.
