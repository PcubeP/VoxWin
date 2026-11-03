# voxwin — Windows daemon

The Windows side of **voxwin**: a small Rust WebSocket server that listens on your
LAN and executes voice commands relayed by the voxwin Android app — volume, media
keys, typing, key combos, scrolling, launching apps, and locking the PC.

Stack: Rust + Tokio + `tokio-tungstenite` (WebSocket) + `enigo` (synthetic input).
This is deliberately a *different* stack from the other projects in this workspace.

## Build

```sh
cargo build --release
```

The binary lands at `target/release/voxwin-daemon.exe`.

## Run

Two optional environment variables:

| Variable        | Default      | Meaning                                  |
| --------------- | ------------ | ---------------------------------------- |
| `VOXWIN_TOKEN`  | `voxwin-dev` | Shared secret the phone must send.       |
| `VOXWIN_PORT`   | `8787`       | TCP port to listen on (`0.0.0.0`).       |

```sh
# PowerShell
$env:VOXWIN_TOKEN="my-secret"; $env:VOXWIN_PORT="8787"; .\target\release\voxwin-daemon.exe

# Git Bash
VOXWIN_TOKEN=my-secret VOXWIN_PORT=8787 ./target/release/voxwin-daemon.exe
```

On startup it prints the **LAN IP + port** it bound to, e.g.:

```
voxwin daemon listening on 0.0.0.0:8787
  -> enter this in the phone app:  192.168.0.42:8787
  -> auth token: voxwin-dev (default - set VOXWIN_TOKEN to change)
```

Type that `IP:port` (and the token) into the Android app to connect.

> Make sure the port is allowed through Windows Firewall for your private network,
> and that the phone is on the same Wi-Fi/LAN.

## Wire protocol (summary)

JSON over a single WebSocket text channel. Every client frame:

```json
{ "type": "command", "token": "voxwin-dev", "action": "volume_up", "args": {} }
```

Actions: `ping`, `volume_up`, `volume_down`, `mute`, `media_play_pause`,
`media_next`, `media_prev`, `launch` (`{"app":"notepad"}`), `type_text`
(`{"text":"hi"}`), `key` (`{"combo":"ctrl+shift+esc"}`), `scroll`
(`{"direction":"up","amount":3}`), `lock`.

Server frames:

```json
{ "type": "hello", "version": "1.0", "host": "MY-PC" }
{ "type": "ack",   "action": "volume_up", "ok": true,  "message": "volume up" }
{ "type": "error", "action": "launch",    "ok": false, "message": "..." }
```

A missing/wrong token gets `{"type":"error","ok":false,"message":"unauthorized"}`
and the connection is closed.

### Launch aliases

`notepad`, `calculator`, `explorer`, `browser` (opens default browser),
`terminal` (Windows Terminal, `wt`). Anything else is run as a raw command/path.
