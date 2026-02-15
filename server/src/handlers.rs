use crate::state::{SharedState, Peer};
use axum::extract::ws::{Message, WebSocket};
use axum::extract::Query;
use futures::{sink::SinkExt, stream::StreamExt};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::{Arc, atomic::Ordering};
use std::time::{SystemTime, UNIX_EPOCH};
use rust_core::messages::{ClientMessage, ServerMessage};
use uuid::Uuid;

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
            let now = get_server_micros();
            let start_time = now + (delay_ms * 1000); 
            
            let cmd = ServerMessage::PlayCommand {
                track_url,
                start_at_server_time: start_time,
                server_time_at_broadcast: now,
            };
            
            tracing::info!("Broadcasting PlayCommand for {} at {}", start_time, now);
            let _ = state.tx.send(cmd);
        }
    }
}

// Helper: Get monotonic-like time in micros
fn get_server_micros() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_micros() as u64
}

use std::sync::atomic;
