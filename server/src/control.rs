use axum::{
    extract::{State, Json},
    http::StatusCode,
    response::IntoResponse,
};
use crate::app_state::SharedState;
use rust_core::messages::{ServerMessage, ControlCommand};
use std::time::{SystemTime, UNIX_EPOCH};

// Helper to get server time
fn get_server_micros() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_micros() as u64
}

// Core logic shared between REST and WebSocket
pub fn process_control_command(state: &SharedState, cmd: ControlCommand) {
    let mut pb_guard = state.playback_state.write().unwrap();
    let now = get_server_micros();
    
    match cmd {
        ControlCommand::Play { start_at_ms, delay_ms } => {
            pb_guard.is_playing = true;
            pb_guard.position_ms = start_at_ms;
            pb_guard.last_update_time = now;
            
            let start_at_server_time = now + (delay_ms * 1000);
            
            // If track_url is empty, check if we have a hosted file
            let track_url = if pb_guard.track_url.is_empty() {
                // If hosted file exists, construct local URL (this part might need IP injection or client handling)
                // For now, let's assume the client knows where to look if it's hosting
                "stream".to_string() 
            } else {
                pb_guard.track_url.clone()
            };

            let msg = ServerMessage::PlayCommand {
                track_url, 
                start_at_server_time,
                start_at_position_ms: start_at_ms,
                server_time_at_broadcast: now,
            };
            let _ = state.tx.send(msg);
        }
        ControlCommand::Pause => {
            // Update position based on how long we played
            if pb_guard.is_playing {
                let elapsed_micros = now - pb_guard.last_update_time;
                pb_guard.position_ms += elapsed_micros / 1000;
            }
            pb_guard.is_playing = false;
            pb_guard.last_update_time = now;
            
            let msg = ServerMessage::PauseCommand {
                server_time: now,
            };
            let _ = state.tx.send(msg);
        }
        ControlCommand::Seek { position_ms } => {
            pb_guard.position_ms = position_ms;
            pb_guard.last_update_time = now;
            
            // If playing, we need to send a new PlayCommand from this position
            if pb_guard.is_playing {
                 let start_at_server_time = now + 500_000; // 500ms buffer
                 let msg = ServerMessage::PlayCommand {
                    track_url: pb_guard.track_url.clone(),
                    start_at_server_time,
                    start_at_position_ms: position_ms,
                    server_time_at_broadcast: now,
                };
                let _ = state.tx.send(msg);
            }
            // If paused, we effectively just updated the "resume from" position
        }
    }
}

// Handler for processing control commands (from REST)
pub async fn handle_control_command(
    State(state): State<SharedState>,
    Json(cmd): Json<ControlCommand>,
) -> impl IntoResponse {
    process_control_command(&state, cmd);
    StatusCode::OK
}
