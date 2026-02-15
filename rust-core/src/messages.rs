use serde::{Deserialize, Serialize};

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
    PlayRequest {
        track_url: String,
        delay_ms: u64,
    }
}

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
        track_url: String,
        start_at_server_time: u64, // Future timestamp
        server_time_at_broadcast: u64,
    },
    SyncRequired // Force client to re-sync
}
