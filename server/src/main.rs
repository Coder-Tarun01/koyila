use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt};

#[tokio::main]
async fn main() {
    tracing_subscriber::registry()
        .with(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "server=info".into()),
        )
        .with(tracing_subscriber::fmt::layer())
        .init();

    let state = server::app_state::AppState::new();
    server::run(3000, state).await;
}
