//! voxwin Windows daemon.
//!
//! Accepts JSON voice commands over two transports concurrently:
//!   • WebSocket (ws:// plain or wss:// TLS) — LAN or internet
//!   • Bluetooth RFCOMM (Windows only) — no network required
//!
//! Environment variables
//!   VOXWIN_PORT   TCP port for the WebSocket server (default 8787)
//!   VOXWIN_TOKEN  Shared secret clients must include in every command (default "voxwin-dev")
//!   VOXWIN_TLS    Set to "1" or "true" to enable wss:// with a self-signed cert

mod actions;
mod protocol;
mod tls;

#[cfg(windows)]
mod bluetooth;

use std::{env, net::SocketAddr, sync::Arc};

use futures_util::{SinkExt, StreamExt};
use tokio::{io::{AsyncRead, AsyncWrite}, net::{TcpListener, TcpStream}};
use tokio_tungstenite::{accept_async, tungstenite::Message};

use protocol::{Command, Server};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let port: u16 = env::var("VOXWIN_PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(8787);
    let token = env::var("VOXWIN_TOKEN").unwrap_or_else(|_| "voxwin-dev".to_string());
    let use_tls = env::var("VOXWIN_TLS")
        .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
        .unwrap_or(false);

    // ── Bluetooth server (Windows only) ──────────────────────────────────────
    #[cfg(windows)]
    {
        let bt_token = token.clone();
        let bt_log: Arc<dyn Fn(String) + Send + Sync + 'static> =
            Arc::new(|msg: String| println!("{msg}"));
        std::thread::spawn(move || bluetooth::run(bt_token, bt_log));
    }

    // ── WebSocket server ──────────────────────────────────────────────────────
    let bind_addr = format!("0.0.0.0:{port}");
    let listener = TcpListener::bind(&bind_addr).await?;

    let lan_ip = local_ip_address::local_ip()
        .map(|ip| ip.to_string())
        .unwrap_or_else(|_| "<unknown-ip>".to_string());
    let scheme = if use_tls { "wss" } else { "ws" };
    println!("voxwin daemon  ──────────────────────────────");
    println!("  WebSocket : {scheme}://{lan_ip}:{port}");
    println!(
        "  Token     : {}",
        if token == "voxwin-dev" {
            "voxwin-dev  (default — set VOXWIN_TOKEN to change)"
        } else {
            "(from VOXWIN_TOKEN)"
        }
    );
    #[cfg(windows)]
    println!("  Bluetooth : RFCOMM / SPP UUID 00001101-0000-1000-8000-00805F9B34FB");

    // Build TLS acceptor once (if enabled) and share it via Arc.
    let tls_acceptor: Option<Arc<tokio_rustls::TlsAcceptor>> = if use_tls {
        let (acceptor, pem) = tls::make_acceptor();
        println!("  TLS       : enabled (self-signed cert)");
        println!("  Cert PEM  : (Android must enable 'Accept self-signed certs' in the app)");
        println!("{pem}");
        Some(Arc::new(acceptor))
    } else {
        None
    };

    println!("──────────────────────────────────────────────");

    // ── Feature 4: JPEG screen-capture stream on port+1 ──────────────────────
    let stream_port: u16 = env::var("VOXWIN_STREAM_PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(port + 1);
    match TcpListener::bind(format!("0.0.0.0:{stream_port}")).await {
        Ok(sl) => {
            println!("  Stream    : ws://{}:{stream_port}  (remote desktop, JPEG 10fps)", lan_ip);
            tokio::spawn(async move {
                loop {
                    match sl.accept().await {
                        Ok((stream, peer)) => {
                            tokio::spawn(async move {
                                if let Err(e) = handle_stream_ws(stream, peer).await {
                                    eprintln!("[stream:{peer}] ended: {e}");
                                }
                            });
                        }
                        Err(e) => eprintln!("stream accept: {e}"),
                    }
                }
            });
        }
        Err(e) => eprintln!("stream port {stream_port} unavailable: {e}"),
    }

    loop {
        match listener.accept().await {
            Ok((stream, peer)) => {
                let token = token.clone();
                let acceptor = tls_acceptor.clone();
                tokio::spawn(async move {
                    if let Err(e) = handle_conn(stream, peer, token, acceptor).await {
                        eprintln!("[{peer}] connection ended: {e}");
                    }
                });
            }
            Err(e) => eprintln!("accept error: {e}"),
        }
    }
}

/// Upgrade the raw TCP stream (plain or TLS) then hand off to the WS handler.
async fn handle_conn(
    stream: TcpStream,
    peer: SocketAddr,
    token: String,
    tls: Option<Arc<tokio_rustls::TlsAcceptor>>,
) -> Result<(), Box<dyn std::error::Error>> {
    match tls {
        Some(acceptor) => {
            let tls_stream = acceptor.accept(stream).await?;
            handle_ws(tls_stream, peer, token).await
        }
        None => handle_ws(stream, peer, token).await,
    }
}

/// Drive one WebSocket client connection for its whole lifetime.
async fn handle_ws<S>(
    stream: S,
    peer: SocketAddr,
    token: String,
) -> Result<(), Box<dyn std::error::Error>>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let mut ws = accept_async(stream).await?;
    println!("[{peer}] connected");

    let host = env::var("COMPUTERNAME").unwrap_or_else(|_| "windows-pc".to_string());
    ws_send(&mut ws, &Server::hello(host)).await?;

    while let Some(frame) = ws.next().await {
        let msg = match frame {
            Ok(m) => m,
            Err(e) => {
                eprintln!("[{peer}] read error: {e}");
                break;
            }
        };

        match msg {
            Message::Text(text) => {
                let cmd: Command = match serde_json::from_str(&text) {
                    Ok(c) => c,
                    Err(e) => {
                        eprintln!("[{peer}] bad frame: {e}");
                        ws_send(&mut ws, &Server::error("unknown", format!("invalid json: {e}")))
                            .await?;
                        continue;
                    }
                };

                if cmd.token != token {
                    eprintln!("[{peer}] unauthorized (action '{}')", cmd.action);
                    ws_send(&mut ws, &Server::error(cmd.action, "unauthorized")).await?;
                    break;
                }

                println!("[{peer}] action: {}  args: {:?}", cmd.action, cmd.args);

                let action = cmd.action.clone();
                let args = cmd.args.clone();
                let result =
                    tokio::task::spawn_blocking(move || actions::dispatch(&action, &args)).await;

                let reply = match result {
                    Ok(Ok(message)) => Server::ack(cmd.action, Some(message)),
                    Ok(Err(e)) => Server::error(cmd.action, e),
                    Err(join_err) => {
                        Server::error(cmd.action, format!("handler panicked: {join_err}"))
                    }
                };
                ws_send(&mut ws, &reply).await?;
            }
            Message::Ping(payload) => ws.send(Message::Pong(payload)).await?,
            Message::Close(_) => {
                println!("[{peer}] client closed");
                break;
            }
            _ => {}
        }
    }

    println!("[{peer}] disconnected");
    Ok(())
}

async fn ws_send<S>(
    ws: &mut tokio_tungstenite::WebSocketStream<S>,
    msg: &Server,
) -> Result<(), Box<dyn std::error::Error>>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let text = serde_json::to_string(msg)?;
    ws.send(Message::Text(text)).await?;
    Ok(())
}

/// Feature 4: stream JPEG screen captures over a WebSocket.
///
/// First frame sent: a JSON text message `{"type":"stream_info","width":W,"height":H}`.
/// Subsequent frames: binary WebSocket frames containing a full JPEG image (50% scale).
/// Streams at ~10fps until the client disconnects.
async fn handle_stream_ws(
    stream: tokio::net::TcpStream,
    peer: std::net::SocketAddr,
) -> Result<(), Box<dyn std::error::Error>> {
    let mut ws = accept_async(stream).await?;
    eprintln!("[stream:{peer}] connected");

    // Probe the screen resolution from an initial capture.
    let (screen_w, screen_h) = tokio::task::spawn_blocking(|| -> (u32, u32) {
        screenshots::Screen::all()
            .ok()
            .and_then(|s| s.into_iter().next())
            .and_then(|screen| screen.capture().ok())
            .map(|img| (img.width(), img.height()))
            .unwrap_or((1920, 1080))
    })
    .await?;

    // Tell the client the original (pre-scale) resolution so it can map touch → abs coords.
    let info = serde_json::json!({
        "type": "stream_info",
        "width":  screen_w,
        "height": screen_h,
    });
    ws.send(Message::Text(serde_json::to_string(&info)?)).await?;

    // Capture loop — 10fps.
    loop {
        let jpeg_opt = tokio::task::spawn_blocking(|| -> Option<Vec<u8>> {
            use image::{DynamicImage, ImageFormat};

            let screen = screenshots::Screen::all().ok()?.into_iter().next()?;
            let rgba = screen.capture().ok()?;
            let (w, h) = (rgba.width(), rgba.height());
            let scaled = DynamicImage::ImageRgba8(rgba)
                .resize(w / 2, h / 2, image::imageops::FilterType::Nearest);
            let mut buf = Vec::new();
            scaled
                .write_to(&mut std::io::Cursor::new(&mut buf), ImageFormat::Jpeg)
                .ok()?;
            Some(buf)
        })
        .await?;

        if let Some(jpeg) = jpeg_opt {
            if ws.send(Message::Binary(jpeg)).await.is_err() {
                break; // client disconnected
            }
        }

        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    }

    eprintln!("[stream:{peer}] disconnected");
    Ok(())
}
