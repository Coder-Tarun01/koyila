use dashmap::DashMap;
use std::sync::{Arc, atomic::AtomicU64, RwLock}; // Added RwLock
use tokio::sync::broadcast;
use rust_core::messages::ServerMessage;

pub type SharedState = Arc<AppState>;

pub struct Peer {
    pub addr: std::net::SocketAddr,
    pub offset: AtomicU64,  // Last calculated offset
    pub rtt: AtomicU64,     // Last calculated RTT
}

#[derive(Debug, Clone)]
pub struct PlaybackState {
    pub is_playing: bool,
    pub track_url: String,
    pub position_ms: u64,
    pub last_update_time: u64, // Server time when this state was updated
}

pub struct AppState {
    // Map of active peer sessions
    pub peers: DashMap<String, Arc<Peer>>,
    // Pub/Sub for broadcasting messages to all connected clients
    pub tx: broadcast::Sender<ServerMessage>,
    
    // Host Mode State
    // Host Mode State
    pub hosted_file_path: Arc<RwLock<Option<String>>>,
    pub playback_state: Arc<RwLock<PlaybackState>>,
    
    // Live Streaming
    pub audio_tx: broadcast::Sender<Vec<u8>>,
}

impl AppState {
    pub fn new() -> SharedState {
        let (tx, _) = broadcast::channel(100);
        let (audio_tx, _) = broadcast::channel(1024);

        Arc::new(Self {
            peers: DashMap::new(),
            tx,
            hosted_file_path: Arc::new(RwLock::new(None)),
            playback_state: Arc::new(RwLock::new(PlaybackState {
                is_playing: false,
                track_url: "".to_string(),
                position_ms: 0,
                last_update_time: 0,
            })),
            audio_tx,
        })
    }
}
