use dashmap::DashMap;
use std::sync::{Arc, atomic::{AtomicU64, Ordering}};
use tokio::sync::broadcast;
use rust_core::messages::ServerMessage;

pub type SharedState = Arc<AppState>;

pub struct Peer {
    pub addr: std::net::SocketAddr,
    pub offset: AtomicU64,  // Last calculated offset
    pub rtt: AtomicU64,     // Last calculated RTT
}

pub struct AppState {
    // Map of active peer sessions
    pub peers: DashMap<String, Arc<Peer>>,
    // Pub/Sub for broadcasting messages to all connected clients
    pub tx: broadcast::Sender<ServerMessage>,
}

impl AppState {
    pub fn new() -> SharedState {
        let (tx, _) = broadcast::channel(100);
        Arc::new(Self {
            peers: DashMap::new(),
            tx,
        })
    }
}
