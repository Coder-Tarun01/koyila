use jni::objects::{JClass, JObject, JString, JValue};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;
use std::sync::{Arc, Mutex};
use tokio::runtime::Runtime;
use once_cell::sync::Lazy;
use rust_core::{messages::{ClientMessage, ServerMessage}, clock::ClockOffset, pid::PidController};
use futures::{SinkExt, StreamExt};
use tokio_tungstenite::{connect_async, tungstenite::protocol::Message};
use url::Url;
use std::time::{SystemTime, UNIX_EPOCH};

// Global state for simple JNI access
static RUNTIME: Lazy<Runtime> = Lazy::new(|| Runtime::new().unwrap());
static CLIENT_STATE: Lazy<Arc<Mutex<ClientState>>> = Lazy::new(|| Arc::new(Mutex::new(ClientState::new())));
static WS_SENDER: Lazy<Arc<Mutex<Option<tokio::sync::mpsc::Sender<ClientMessage>>>>> = Lazy::new(|| Arc::new(Mutex::new(None)));

struct ClientState {
    offset: i64,
    rtt: u64,
    drift: f64,
    pid: PidController,
}

impl ClientState {
    fn new() -> Self {
        Self {
            offset: 0,
            rtt: 0,
            drift: 0.0,
            pid: PidController::new(0.005, 0.0001, 0.001), // Tuned for audio
        }
    }
}

// Initialize logger
#[no_mangle]
pub extern "system" fn Java_com_sonicsync_app_SonicSyncEngine_initLogger(_env: JNIEnv, _class: JClass) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );
    log::info!("Rust Logger Initialized");
}

// Connect to WebSocket
#[no_mangle]
pub extern "system" fn Java_com_sonicsync_app_SonicSyncEngine_connect(
    mut env: JNIEnv,
    _class: JClass,
    j_url: JString,
    j_callback: JObject 
) {
    let url_str: String = env.get_string(&j_url).expect("Couldn't get java string!").into();
    log::info!("Connecting to: {}", url_str);
    
    // Create global ref for callback to use in thread
    let jvm = env.get_java_vm().unwrap();
    let callback_ref = env.new_global_ref(j_callback).unwrap();

    RUNTIME.spawn(async move {
        match connect_async(Url::parse(&url_str).unwrap()).await {
            Ok((ws_stream, _)) => {
                log::info!("WebSocket Connected");
                let (mut write, mut read) = ws_stream.split();
                
                // Create a channel for outgoing messages
                let (tx, mut rx) = tokio::sync::mpsc::channel::<ClientMessage>(32);
                *WS_SENDER.lock().unwrap() = Some(tx.clone());

                // Join
                let join_msg = ClientMessage::Join {
                     device_id: format!("ANDROID-{}", uuid::Uuid::new_v4()),
                };
                let _ = write.send(Message::Binary(bincode::serialize(&join_msg).unwrap())).await;
                
                // WebSocket Write/Read split logic
                let mut write_half = write;

                loop {
                    tokio::select! {
                        // Handle outgoing messages from channel
                        Some(client_msg) = rx.recv() => {
                            if let Ok(bytes) = bincode::serialize(&client_msg) {
                                if write_half.send(Message::Binary(bytes)).await.is_err() {
                                    break;
                                }
                            }
                        }

                        // Handle incoming messages from socket
                        msg = read.next() => {
                            let msg = match msg {
                                Some(Ok(m)) => m,
                                Some(Err(e)) => {
                                    log::error!("WebSocket read error: {:?}", e);
                                    break;
                                },
                                None => { // Stream ended
                                    log::info!("WebSocket stream ended.");
                                    break;
                                }
                            };

                            match msg {
                                Message::Binary(bytes) => {
                                    if let Ok(server_msg) = bincode::deserialize::<ServerMessage>(&bytes) {
                                        match server_msg {
                                            ServerMessage::TimeResponse { t0, t1, t2, .. } => {
                                                let t3 = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_micros() as u64;
                                                let stats = ClockOffset::calculate(t0, t1, t2, t3);
                                                
                                                let mut state = CLIENT_STATE.lock().unwrap();
                                                state.offset = stats.offset;
                                                state.rtt = stats.rtt;
                                                log::debug!("Sync Updated: Offset={}us RTT={}us", stats.offset, stats.rtt);
                                            }
                                            ServerMessage::PlayCommand { track_url, start_at_server_time, .. } => {
                                                log::info!("Received PlayCommand: {} @ {}", track_url, start_at_server_time);
                                                
                                                // Call Java callback
                                                if let Ok(mut env) = jvm.attach_current_thread() {
                                                     let url_jstr = match env.new_string(&track_url) {
                                                         Ok(s) => s,
                                                         Err(_) => {
                                                             log::error!("Failed to create Java string for URL");
                                                             continue;
                                                         }
                                                     };
                                                     let offset = match CLIENT_STATE.lock() {
                                                         Ok(guard) => guard.offset,
                                                         Err(_) => 0,
                                                     };
                                                     
                                                     let _ = env.call_method(
                                                         &callback_ref,
                                                         "onPlayCommand",
                                                         "(Ljava/lang/String;JJ)V",
                                                         &[
                                                             JValue::Object(&url_jstr),
                                                             JValue::Long(start_at_server_time as i64),
                                                             JValue::Long(offset)
                                                         ]
                                                     );
                                                }
                                            }
                                            _ => {}
                                        }
                                    }
                                }
                                Message::Close(_) => {
                                    log::info!("WebSocket received close frame.");
                                    break;
                                },
                                _ => {}
                            }
                        }
                    }
                }
                *WS_SENDER.lock().unwrap() = None;
                log::info!("WebSocket Disconnected");
            },
            Err(e) => log::error!("Connection failed: {:?}", e),
        }
    });
}

// Request Play (Host control)
#[no_mangle]
pub extern "system" fn Java_com_sonicsync_app_SonicSyncEngine_requestPlay(
    mut env: JNIEnv, 
    _class: JClass, 
    j_url: JString,
    delay_ms: jlong
) {
    let url_res: Result<String, _> = env.get_string(&j_url).map(|s| s.into());
    let url = match url_res {
        Ok(s) => s,
        Err(_) => {
            log::error!("requestPlay: Invalid URL string from Java");
            return;
        }
    };

    let msg = ClientMessage::PlayRequest {
        track_url: url,
        delay_ms: delay_ms as u64,
    };

    if let Ok(guard) = WS_SENDER.lock() {
        if let Some(tx) = guard.as_ref() {
            let tx = tx.clone();
            RUNTIME.spawn(async move {
                let _ = tx.send(msg).await;
            });
        } else {
            log::error!("Cannot send PlayRequest: Not connected to server");
        }
    } else {
        log::error!("WS_SENDER lock poisoned");
    }
}

// Trigger Sync
#[no_mangle]
pub extern "system" fn Java_com_sonicsync_app_SonicSyncEngine_sendSyncRequest(_env: JNIEnv, _class: JClass) {
    let t0 = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_micros() as u64;
    let msg = ClientMessage::TimeRequest { t0, seq: 0 };
    
    if let Some(tx) = WS_SENDER.lock().unwrap().as_ref() {
        let tx = tx.clone();
        RUNTIME.spawn(async move {
            let _ = tx.send(msg).await;
        });
    }
}

// Get Offset
#[no_mangle]
pub extern "system" fn Java_com_sonicsync_app_SonicSyncEngine_getOffset(_env: JNIEnv, _class: JClass) -> jlong {
    CLIENT_STATE.lock().unwrap().offset
}

// Calculate drift correction
// Returns playback speed multiplier (e.g., 1.001)
#[no_mangle]
pub extern "system" fn Java_com_sonicsync_app_SonicSyncEngine_calculateCorrection(
    _env: JNIEnv, 
    _class: JClass,
    drift_ms: jlong,
    dt_seconds: f64
) -> f64 {
    let mut state = CLIENT_STATE.lock().unwrap();
    state.drift = drift_ms as f64; // Store last drift
    
    // Target is 0 drift. Error = 0 - drift.
    let drift = state.drift;
    let correction = state.pid.next(-drift, dt_seconds);

    
    // Base speed 1.0 + correction
    // Clamp to safe limits (0.95 to 1.05) to avoid audio artifacts, though PID should be tighter
    let speed = 1.0 + correction;
    speed.clamp(0.95, 1.05)
}

// Current Client Timestamp (in Server Time approximation)
#[no_mangle]
pub extern "system" fn Java_com_sonicsync_app_SonicSyncEngine_getServerTime(_env: JNIEnv, _class: JClass) -> jlong {
    let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_micros() as u64;
    let offset = CLIENT_STATE.lock().unwrap().offset;
    (now as i64 + offset) as jlong
}
