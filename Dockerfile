# Build stage
FROM rust:1.83-slim AS builder

WORKDIR /app

# Copy workspace files
COPY Cargo.toml Cargo.lock ./
COPY rust-core ./rust-core
COPY server ./server

# Create dummy crates for other workspace members to satisfy workspace config
RUN mkdir -p cli-client/src && echo "fn main() {}" > cli-client/src/main.rs
RUN mkdir -p android-bridge/src && echo "" > android-bridge/src/lib.rs

# Create minimal Cargo.toml for dummy crates
RUN echo '[package]\nname = "cli-client"\nversion = "0.1.0"\nedition = "2021"' > cli-client/Cargo.toml
RUN echo '[package]\nname = "android-bridge"\nversion = "0.1.0"\nedition = "2021"\n\n[lib]\ncrate-type = ["cdylib"]' > android-bridge/Cargo.toml

# Build only the server
RUN cargo build --package server --release

# Runtime stage - minimal image
FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/target/release/server /usr/local/bin/server

EXPOSE 3000

CMD ["server"]
