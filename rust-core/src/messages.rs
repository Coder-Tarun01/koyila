use serde::{Deserialize, Serialize};



#[derive(Serialize, Deserialize, Debug, Clone)]
pub enum ServerMessage {
    Welcome { session_id: String },
    TimeResponse { 
        t0: u64, 
        t1: u64, // Server receive time
        t2: u64, // Server transmit time
        seq: u8 
    },
    PlayCommand {
        track_url: String, // If playing a new track
        start_at_server_time: u64, // Future timestamp for sync start
        start_at_position_ms: u64, // Where in the track to start (e.g. 0 for new, X for resume)
        server_time_at_broadcast: u64,
    },
    PauseCommand {
        server_time: u64, // When the pause happened
    },
    SyncRequired // Force client to re-sync
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub enum ClientMessage {
    Join { device_id: String },
    TimeRequest { t0: u64, seq: u8 }, // t0 = client send time
    Telemetry { 
        rtt: u64,
        offset: i64,
        drift: i64,
        status: String
    },
    PlayRequest { // Request to play a URL generally
        track_url: String,
        delay_ms: u64,
    },
    CommandRequest { // Control commands (Play/Pause/Seek)
        cmd: ControlCommand
    }
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub enum ControlCommand {
    Play { 
        start_at_ms: u64, // Position in track
        delay_ms: u64 
    },
    Pause,
    Seek { position_ms: u64 }
}
