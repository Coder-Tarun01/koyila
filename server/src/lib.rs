pub mod app_state;
pub mod handlers;
pub mod stream;
pub mod control;
pub mod routes;

use std::net::SocketAddr;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

pub use app_state::AppState; // Re-export for convenience

pub async fn run(port: u16, app_state: app_state::SharedState) {
    // Initialize tracing if not already initialized
    // ...
    
    let app = routes::create_router(app_state);

    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    tracing::info!("Server listening on {}", addr);
    
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .await
    .unwrap();
}
