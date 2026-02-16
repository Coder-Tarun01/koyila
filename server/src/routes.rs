use axum::{
    routing::{get, post},
    Router,
};
use crate::app_state::SharedState;
use crate::{handlers, stream, control};

pub fn create_router(state: SharedState) -> Router {
    Router::new()
        .route("/ws", get(handlers::ws_handler))
        .route("/stream", get(stream::stream_audio))
        .route("/stream/live", get(stream::live_stream))
        .route("/control", post(control::handle_control_command))
        .with_state(state)
}
