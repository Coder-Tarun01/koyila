# Sonicsync Phase 1: Core & Server Implementation

This phase delivers a working Rust backend and a CLI test client to verify the synchronization protocol.

## Prerequisites
- Rust (latest stable)
- Cargo

## Directory Structure
- `/server`: The Authoritative Time Server (WebSocket).
- `/cli-client`: A test client to verify sync and playback.
- `/rust-core`: Shared library containing protocol definitions and sync logic.

## How to Build
From the root directory, run:
```bash
cargo build --workspace
```

## How to Run

### 1. Start the Server
Open a terminal and run:
```bash
RUST_LOG=info cargo run --bin server
```
Server will start on `0.0.0.0:3000`.

### 2. Start a Client (Listener)
Open a **second terminal** and run:
```bash
cargo run --bin cli-client
```
This client will:
1. Connect to the server.
2. Perform a burst of 5 sync requests.
3. Calculate and display the clock offset.
4. Wait for a Play command.

### 3. Start a Host Client (Trigger Playback)
Open a **third terminal** and run:
```bash
cargo run --bin cli-client -- host
```
*(Note: Use `-- host` to pass the argument to the binary)*

This client will:
1. Connect and sync (same as above).
2. Send a `PlayRequest` to the server (requesting playback in 3 seconds).
3. The server will broadcast the `PlayCommand`.
4. **All clients** (including this one and the listener) will receive the command and count down to the target timestamp.

## Android Client (Phase 2)
The Android client is located in `/android-client`.

### Prerequisites
- Android Studio with NDK installed.
- Rust Cargo NDK: `cargo install cargo-ndk`
- Add android targets: `rustup target add aarch64-linux-android`

### How to Build
1. Open `/android-client` in Android Studio.
2. The `sonicsync_bridge` library must be compiled and placed in `jniLibs`.
   - Run: `cd android-bridge && cargo ndk -t aarch64-linux-android -o ../android-client/app/src/main/jniLibs build --release`
3. Build and Run the App on a physical device.
4. Enter your computer's local IP (e.g., `192.168.1.X`) in the UI and click Connect.

## Dashboard (Phase 3)
The React dashboard is located in `/dashboard`.

### How to Run
1. Navigate to `/dashboard`::
   ```bash
   cd dashboard
   npm install
   npm run dev
   ```
2. Open `http://localhost:5173`.
3. It will automatically connect to the local Rust server.
4. Click "BROADCAST PLAY" to trigger playback on all connected Android devices.

## Verification
You should see output similar to this:

**Server:**
```
INFO listening on 0.0.0.0:3000
INFO Client connecting: 127.0.0.1:xyz
INFO Broadcasting PlayCommand for 1234567890 at 1234564890
```

**Client:**
```
Sync #0: RTT=150us Offset=1200us
...
--- SYNC COMPLETE. AVG OFFSET: 1250us ---
>>> PLAY COMMAND RECEIVED <<<
Time until play: 2995ms
!!! PLAYING NOW !!!
```
