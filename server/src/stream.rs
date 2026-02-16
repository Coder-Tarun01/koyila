use axum::{
    extract::State,
    http::StatusCode,
    response::{IntoResponse, Response},
    body::Body,
};
use crate::app_state::SharedState;
use tower_http::services::ServeFile;
use tower::ServiceExt; // for Request

pub async fn stream_audio(State(state): State<SharedState>) -> Response {
    let file_path = {
        let guard = state.hosted_file_path.read().unwrap();
        guard.clone()
    };

    match file_path {
        Some(path) => {
            let req = axum::http::Request::builder()
                .body(Body::empty())
                .unwrap();
                
            // Use tower_http to serve the file with range support
            match ServeFile::new(path).oneshot(req).await {
                Ok(res) => res.into_response(),
                Err(err) => {
                    tracing::error!("Failed to serve file: {}", err);
                    (StatusCode::INTERNAL_SERVER_ERROR, "Failed to serve file").into_response()
                }
            }
        }
        None => (StatusCode::NOT_FOUND, "No file hosted").into_response(),
    }
}

pub async fn live_stream(State(state): State<SharedState>) -> Response {
    let mut rx = state.audio_tx.subscribe();
    
    let stream = async_stream::stream! {
        loop {
            match rx.recv().await {
                Ok(data) => yield Ok::<_, std::io::Error>(axum::body::Bytes::from(data)),
                Err(e) => {
                    tracing::warn!("Audio broadcast stream error: {}", e);
                    // If lagged, we might miss some chunks but should continue or reconnect logic?
                    // For now, simple continue if lagged, break if closed.
                     match e {
                        tokio::sync::broadcast::error::RecvError::Lagged(_) => continue,
                        tokio::sync::broadcast::error::RecvError::Closed => break,
                    }
                }
            }
        }
    };
    
    // Set headers for live streaming
    // Content-Type: audio/aac (if ADTS) or application/octet-stream
    // Transfer-Encoding: chunked is handled by Body::from_stream
    
    Body::from_stream(stream).into_response()
}
