//! Bluetooth RFCOMM server (Windows-only).
//!
//! Opens an `AF_BTH`/`BTHPROTO_RFCOMM` socket, registers an SDP record under
//! the standard Serial Port Profile UUID so Android can discover it with
//! `createRfcommSocketToServiceRecord`, then accepts connections and speaks the
//! same newline-delimited JSON protocol used over the WebSocket transport.

#![cfg(windows)]

use std::{
    io::{BufRead, BufReader, BufWriter, Write},
    mem,
    net::TcpStream,
    os::windows::io::FromRawSocket,
    ptr,
    sync::Arc,
};

use windows::{
    core::{GUID, PCWSTR, PWSTR},
    Win32::{
        Devices::Bluetooth::{BTHPROTO_RFCOMM, BT_PORT_ANY, SOCKADDR_BTH},
        Networking::WinSock::{
            accept, bind, closesocket, getsockname, listen, socket, WSACleanup, WSASetServiceW,
            WSAStartup, CSADDR_INFO, INVALID_SOCKET, NS_BTH, RNRSERVICE_REGISTER, SOCKADDR,
            SOCK_STREAM, SOCKET_ADDRESS, SOCKET_ERROR, WSADATA, WSAQUERYSETW,
        },
    },
};

use crate::{
    actions,
    protocol::{Command, Server},
};

// AF_BTH = 32 (bthdef.h); not re-exported by name in the windows crate's WinSock module.
const AF_BTH: i32 = 32;

// Standard Serial Port Profile UUID — Android's createRfcommSocketToServiceRecord looks for this.
const SPP_UUID: GUID = GUID {
    data1: 0x0000_1101,
    data2: 0x0000,
    data3: 0x1000,
    data4: [0x80, 0x00, 0x00, 0x80, 0x5F, 0x9B, 0x34, 0xFB],
};

/// Blocking Bluetooth RFCOMM server. Run this on a dedicated `std::thread`.
pub fn run(token: String, log: Arc<dyn Fn(String) + Send + Sync + 'static>) {
    if let Err(e) = run_inner(token, Arc::clone(&log)) {
        log(format!("[bt] server error: {e}"));
    }
}

fn run_inner(
    token: String,
    log: Arc<dyn Fn(String) + Send + Sync + 'static>,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    unsafe {
        // ── Winsock startup ──────────────────────────────────────────────────
        let mut wsa: WSADATA = mem::zeroed();
        if WSAStartup(0x0202, &mut wsa) != 0 {
            return Err("WSAStartup failed — is Winsock available?".into());
        }

        // ── Create RFCOMM socket ─────────────────────────────────────────────
        let srv = socket(AF_BTH, SOCK_STREAM.0 as i32, BTHPROTO_RFCOMM as i32);
        if srv == INVALID_SOCKET {
            WSACleanup();
            return Err("socket() failed — is Bluetooth enabled?".into());
        }

        // ── Bind to any local adapter / any free channel ─────────────────────
        let mut local: SOCKADDR_BTH = mem::zeroed();
        local.addressFamily = AF_BTH as u16;
        local.port = BT_PORT_ANY;
        if bind(
            srv,
            &local as *const _ as *const SOCKADDR,
            mem::size_of::<SOCKADDR_BTH>() as i32,
        ) == SOCKET_ERROR
        {
            closesocket(srv);
            WSACleanup();
            return Err("bind() failed".into());
        }

        // ── Read the assigned channel ────────────────────────────────────────
        let mut bound: SOCKADDR_BTH = mem::zeroed();
        let mut bound_len = mem::size_of::<SOCKADDR_BTH>() as i32;
        getsockname(
            srv,
            &mut bound as *mut _ as *mut SOCKADDR,
            &mut bound_len,
        );
        let channel = bound.port;

        // ── Register SDP record (best-effort) ────────────────────────────────
        if !register_sdp(&mut bound) {
            log("[bt] SDP registration failed — Android discovery by UUID may not work; use manual channel".into());
        }

        // ── Listen ───────────────────────────────────────────────────────────
        if listen(srv, 4) == SOCKET_ERROR {
            closesocket(srv);
            WSACleanup();
            return Err("listen() failed".into());
        }

        log(format!(
            "[bt] RFCOMM listening — channel {channel}, UUID 00001101-0000-1000-8000-00805F9B34FB"
        ));

        // ── Accept loop ──────────────────────────────────────────────────────
        loop {
            let conn = accept(srv, ptr::null_mut(), ptr::null_mut());
            if conn == INVALID_SOCKET {
                log("[bt] accept() failed, Bluetooth server shutting down".into());
                break;
            }
            let tok = token.clone();
            let log2 = Arc::clone(&log);
            std::thread::spawn(move || handle_conn(conn, tok, log2));
        }

        closesocket(srv);
        WSACleanup();
        Ok(())
    }
}

/// Register this service in the local SDP database using the SPP UUID.
/// Returns true on success, false if WSASetService failed (non-fatal).
unsafe fn register_sdp(bound: &mut SOCKADDR_BTH) -> bool {
    let mut cs: CSADDR_INFO = mem::zeroed();
    cs.LocalAddr = SOCKET_ADDRESS {
        lpSockaddr: bound as *mut _ as *mut SOCKADDR,
        iSockaddrLength: mem::size_of::<SOCKADDR_BTH>() as i32,
    };
    cs.RemoteAddr = SOCKET_ADDRESS {
        lpSockaddr: bound as *mut _ as *mut SOCKADDR,
        iSockaddrLength: mem::size_of::<SOCKADDR_BTH>() as i32,
    };
    cs.iSocketType = SOCK_STREAM.0 as i32;
    cs.iProtocol = BTHPROTO_RFCOMM as i32;

    let mut uuid = SPP_UUID;

    // Wide-string service name and comment (null-terminated UTF-16).
    let mut name: Vec<u16> = "voxwin\0".encode_utf16().collect();
    let mut comment: Vec<u16> = "voxwin voice control\0".encode_utf16().collect();

    let mut qs: WSAQUERYSETW = mem::zeroed();
    qs.dwSize = mem::size_of::<WSAQUERYSETW>() as u32;
    qs.dwNameSpace = NS_BTH;
    qs.lpServiceClassId = &mut uuid;
    qs.lpszServiceInstanceName = PWSTR::from_raw(name.as_mut_ptr());
    qs.lpszComment = PWSTR::from_raw(comment.as_mut_ptr());
    qs.dwNumberOfCsAddrs = 1;
    qs.lpcsaBuffer = &mut cs;

    WSASetServiceW(&qs, RNRSERVICE_REGISTER, 0) != SOCKET_ERROR
}

/// Drive one accepted Bluetooth connection for its lifetime.
fn handle_conn(
    conn: windows::Win32::Networking::WinSock::SOCKET,
    token: String,
    log: Arc<dyn Fn(String) + Send + Sync + 'static>,
) {
    // Wrap the raw SOCKET handle as a TcpStream so we get standard Read/Write.
    // Bluetooth RFCOMM sockets are stream sockets; the stdlib I/O operations work.
    let raw = conn.0 as std::os::windows::io::RawSocket;
    let stream = unsafe { TcpStream::from_raw_socket(raw) };
    let write_half = match stream.try_clone() {
        Ok(w) => w,
        Err(e) => {
            log(format!("[bt] clone failed: {e}"));
            return;
        }
    };
    let mut out = BufWriter::new(write_half);
    let reader = BufReader::new(stream);

    // Greet the client.
    let host = std::env::var("COMPUTERNAME").unwrap_or_else(|_| "windows-pc".into());
    let hello = serde_json::to_string(&Server::hello(host)).unwrap_or_default();
    let _ = writeln!(out, "{hello}");
    let _ = out.flush();

    // Process one JSON line at a time.
    for line in reader.lines() {
        let line = match line {
            Ok(l) if !l.trim().is_empty() => l,
            Ok(_) => continue,
            Err(_) => break,
        };

        let cmd: Command = match serde_json::from_str(&line) {
            Ok(c) => c,
            Err(e) => {
                let reply = serde_json::to_string(&Server::error("unknown", format!("invalid json: {e}"))).unwrap_or_default();
                let _ = writeln!(out, "{reply}");
                let _ = out.flush();
                continue;
            }
        };

        if cmd.token != token {
            let reply = serde_json::to_string(&Server::error(&cmd.action, "unauthorized")).unwrap_or_default();
            let _ = writeln!(out, "{reply}");
            let _ = out.flush();
            break;
        }

        let action = cmd.action.clone();
        let args = cmd.args.clone();
        let result = actions::dispatch(&action, &args);

        let reply = match result {
            Ok(msg) => Server::ack(action, Some(msg)),
            Err(e) => Server::error(action, e),
        };
        let reply_str = serde_json::to_string(&reply).unwrap_or_default();
        let _ = writeln!(out, "{reply_str}");
        let _ = out.flush();
    }

    log("[bt] client disconnected".into());
}
