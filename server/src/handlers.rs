use crate::app_state::{SharedState, Peer};
use axum::{
    extract::{ws::{Message, WebSocket, WebSocketUpgrade}, ConnectInfo, Query, State},
    response::IntoResponse,
};
use futures::{sink::SinkExt, stream::StreamExt};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::{Arc, atomic::{Ordering, self}};
use std::time::{SystemTime, UNIX_EPOCH};
use rust_core::messages::{ClientMessage, ServerMessage};
use uuid::Uuid;

pub async fn ws_handler(
    ws: WebSocketUpgrade,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    State(state): State<SharedState>,
    query: Query<HashMap<String, String>>,
) -> impl IntoResponse {
    tracing::info!("Client connecting: {}", addr);
    ws.on_upgrade(move |socket| handle_socket(socket, addr, state, query))
}

pub async fn handle_socket(
    socket: WebSocket,
    addr: SocketAddr,
    state: SharedState,
    query: Query<HashMap<String, String>>,
) {
    let session_id = Uuid::new_v4().to_string();
    let is_dashboard = query.get("type").map(|t| t == "dashboard").unwrap_or(false);

    // Register peer
    state.peers.insert(session_id.clone(), Arc::new(Peer {
        addr,
        offset: atomic::AtomicU64::new(0),
        rtt: atomic::AtomicU64::new(0),
    }));

    let (mut sender, mut receiver) = socket.split();
    
    // Send Welcome directly
    let welcome = ServerMessage::Welcome { session_id: session_id.clone() };
    if is_dashboard {
        if let Ok(json) = serde_json::to_string(&welcome) {
            if sender.send(Message::Text(json)).await.is_err() {
                return;
            }
        }
    } else {
        if let Ok(data) = bincode::serialize(&welcome) {
            if sender.send(Message::Binary(data)).await.is_err() {
                return;
            }
        }
    }

    // State Relay: If server is already playing, send the current track and position to the new client
    let relay_msg = {
        let pb = state.playback_state.read().unwrap();
        if pb.is_playing {
            let now = get_server_micros();
            let current_pos = pb.position_ms + ((now - pb.last_update_time) / 1000);
            Some(ServerMessage::PlayCommand {
                track_url: pb.track_url.clone(),
                start_at_server_time: now, // Start immediately
                start_at_position_ms: current_pos,
                server_time_at_broadcast: now,
            })
        } else {
            None
        }
    };

    if let Some(msg) = relay_msg {
        if is_dashboard {
            if let Ok(json) = serde_json::to_string(&msg) {
                if sender.send(Message::Text(json)).await.is_err() {
                    return;
                }
            }
        } else {
            if let Ok(data) = bincode::serialize(&msg) {
                if sender.send(Message::Binary(data)).await.is_err() {
                    return;
                }
            }
        }
    }

    // Subscribe to broadcast channel
    let mut rx = state.tx.subscribe();

    // Loop selection
    loop {
        tokio::select! {
            // 1. Broadcast messages from other parts of the system
            Ok(msg) = rx.recv() => {
                if is_dashboard {
                    if let Ok(json) = serde_json::to_string(&msg) {
                        if sender.send(Message::Text(json)).await.is_err() {
                            break;
                        }
                    }
                } else {
                    if let Ok(data) = bincode::serialize(&msg) {
                        if sender.send(Message::Binary(data)).await.is_err() {
                            break;
                        }
                    }
                }
            }

            // 2. Incoming messages from this client
            Some(Ok(msg)) = receiver.next() => {
                match msg {
                    Message::Binary(bytes) => {
                         if let Ok(client_msg) = bincode::deserialize::<ClientMessage>(&bytes) {
                            handle_client_message(client_msg, &mut sender, &state, &session_id, is_dashboard).await;
                        }
                    }
                    Message::Text(text) => {
                        if let Ok(client_msg) = serde_json::from_str::<ClientMessage>(&text) {
                            handle_client_message(client_msg, &mut sender, &state, &session_id, is_dashboard).await;
                        }
                    }
                    Message::Close(_) => break,
                    _ => {}
                }
            }
            
            else => break,
        }
    }

    state.peers.remove(&session_id);
    tracing::info!("Client disconnected: {}", session_id);
}

async fn handle_client_message(
    msg: ClientMessage, 
    sender: &mut futures::stream::SplitSink<WebSocket, Message>,
    state: &SharedState, 
    session_id: &String,
    is_dashboard: bool
) {
    match msg {
        ClientMessage::Join { device_id } => {
            tracing::info!("Device joined: {} ({})", device_id, session_id);
        }
        ClientMessage::TimeRequest { t0, seq } => {
            let t1 = get_server_micros();
            let t2 = get_server_micros();
            
            let resp = ServerMessage::TimeResponse { t0, t1, t2, seq };
            
            if is_dashboard {
                if let Ok(json) = serde_json::to_string(&resp) {
                    let _ = sender.send(Message::Text(json)).await;
                }
            } else {
                if let Ok(data) = bincode::serialize(&resp) {
                    let _ = sender.send(Message::Binary(data)).await;
                }
            }
        }
        ClientMessage::Telemetry { rtt, offset, .. } => {
             if let Some(peer) = state.peers.get(session_id) {
                peer.rtt.store(rtt, Ordering::Relaxed);
                peer.offset.store(offset as u64, Ordering::Relaxed);
             }
        }
        ClientMessage::PlayRequest { track_url, delay_ms } => {
            // ... (keep existing logic for external URLs if needed, or deprecate)
             let state = state.clone();
            let session_id = session_id.clone();
            
            // Spawn resolution in a separate task so we don't block the heartbeats
            tokio::spawn(async move {
                 // ... (keep existing URL resolution logic)
                 // For now, just broadcasting PlayCommand as before but mapping to new fields
                 let now = get_server_micros();
                 let start_time = now + (delay_ms * 1000); 
                 
                 let cmd = ServerMessage::PlayCommand {
                    track_url,
                    start_at_server_time: start_time,
                    start_at_position_ms: 0, 
                    server_time_at_broadcast: now,
                };
                let _ = state.tx.send(cmd);
            });
        }
        ClientMessage::CommandRequest { cmd } => {
            crate::control::process_control_command(state, cmd);
        }
    }
}

async fn resolve_audio_url(url: &str) -> anyhow::Result<String> {
    use tokio::process::Command;
    use std::process::Stdio;

    let output = Command::new("yt-dlp")
        .arg("-g")
        .arg("--format")
        .arg("bestaudio")
        .arg(url)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()?
        .wait_with_output()
        .await?;

    if output.status.success() {
        let stdout = String::from_utf8(output.stdout)?;
        // Take ONLY the first line (first URL) in case yt-dlp returns multiple
        let first_url = stdout.lines().next().unwrap_or("").trim().to_string();
        if first_url.is_empty() {
             anyhow::bail!("yt-dlp returned empty URL")
        }
        Ok(first_url)
    } else {
        let stderr = String::from_utf8_lossy(&output.stderr);
        anyhow::bail!("yt-dlp failed: {}", stderr)
    }
}

// Helper: Get monotonic-like time in micros
fn get_server_micros() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_micros() as u64
}


