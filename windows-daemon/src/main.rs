//! voxwin Windows daemon.
//!
//! A tiny WebSocket server that accepts JSON voice commands from the voxwin
//! Android app (over the LAN) and drives Windows input: volume, media keys,
//! typing, key combos, scrolling, launching apps and locking the session.

mod actions;
mod protocol;

use std::env;
use std::net::SocketAddr;

use futures_util::{SinkExt, StreamExt};
use tokio::net::{TcpListener, TcpStream};
use tokio_tungstenite::accept_async;
use tokio_tungstenite::tungstenite::Message;

use protocol::{Command, Server};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Config from env (both optional).
    let port: u16 = env::var("VOXWIN_PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(8787);
    let token = env::var("VOXWIN_TOKEN").unwrap_or_else(|_| "voxwin-dev".to_string());

    let bind_addr = format!("0.0.0.0:{port}");
    let listener = TcpListener::bind(&bind_addr).await?;

    // Print the LAN IP so the user can type it into the phone app.
    let lan_ip = local_ip_address::local_ip()
        .map(|ip| ip.to_string())
        .unwrap_or_else(|_| "<unknown-ip>".to_string());
    println!("voxwin daemon listening on 0.0.0.0:{port}");
    println!("  -> enter this in the phone app:  {lan_ip}:{port}");
    println!(
        "  -> auth token: {}",
        if token == "voxwin-dev" {
            "voxwin-dev (default - set VOXWIN_TOKEN to change)"
        } else {
            "(from VOXWIN_TOKEN)"
        }
    );

    // Accept connections forever; each client gets its own task.
    loop {
        match listener.accept().await {
            Ok((stream, peer)) => {
                let token = token.clone();
                tokio::spawn(async move {
                    if let Err(e) = handle_conn(stream, peer, token).await {
                        eprintln!("[{peer}] connection ended: {e}");
                    }
                });
            }
            Err(e) => eprintln!("accept error: {e}"),
        }
    }
}

/// Handle one client connection for its whole lifetime.
async fn handle_conn(
    stream: TcpStream,
    peer: SocketAddr,
    token: String,
) -> Result<(), Box<dyn std::error::Error>> {
    let mut ws = accept_async(stream).await?;
    println!("[{peer}] connected");

    // Greet the client once.
    let host = env::var("COMPUTERNAME").unwrap_or_else(|_| "windows-pc".to_string());
    send(&mut ws, &Server::hello(host)).await?;

    // Process frames until the socket closes. A single bad frame never breaks
    // the loop (and therefore never crashes the server).
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
                // Parse: a malformed frame just gets an error reply.
                let cmd: Command = match serde_json::from_str(&text) {
                    Ok(c) => c,
                    Err(e) => {
                        eprintln!("[{peer}] bad frame: {e}");
                        send(
                            &mut ws,
                            &Server::error("unknown", format!("invalid json: {e}")),
                        )
                        .await?;
                        continue;
                    }
                };

                // Auth: wrong/missing token -> error + close.
                if cmd.token != token {
                    eprintln!("[{peer}] unauthorized (action '{}')", cmd.action);
                    send(&mut ws, &Server::error(cmd.action, "unauthorized")).await?;
                    break;
                }

                println!("[{peer}] action: {}  args: {:?}", cmd.action, cmd.args);

                // Run the blocking handler off the async runtime.
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
                send(&mut ws, &reply).await?;
            }
            Message::Ping(payload) => ws.send(Message::Pong(payload)).await?,
            Message::Close(_) => {
                println!("[{peer}] client closed");
                break;
            }
            // Binary / Pong / other frames are ignored.
            _ => {}
        }
    }

    println!("[{peer}] disconnected");
    Ok(())
}

/// Serialize a server frame and send it as WebSocket text.
async fn send(
    ws: &mut tokio_tungstenite::WebSocketStream<TcpStream>,
    msg: &Server,
) -> Result<(), Box<dyn std::error::Error>> {
    let text = serde_json::to_string(msg)?;
    ws.send(Message::Text(text)).await?;
    Ok(())
}
