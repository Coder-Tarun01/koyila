use futures::{SinkExt, StreamExt};
use rust_core::{
    clock::ClockOffset,
    messages::{ClientMessage, ServerMessage},
};
use std::time::{SystemTime, UNIX_EPOCH};
use tokio_tungstenite::{connect_async, tungstenite::protocol::Message};
use url::Url;

#[tokio::main]
async fn main() {
    let connect_addr = "ws://127.0.0.1:3000/ws";
    let url = Url::parse(connect_addr).unwrap();

    let (ws_stream, _) = connect_async(url).await.expect("Failed to connect");
    println!("Connected to {}", connect_addr);

    let (mut write, mut read) = ws_stream.split();

    // 1. Join
    let join_msg = ClientMessage::Join {
        device_id: format!("CLI-{}", uuid::Uuid::new_v4()),
    };
    send_msg(&mut write, join_msg).await;

    // 2. Perform Sync (Burst)
    let mut offset_stats = Vec::new();
    let burst_count = 5;

    for i in 0..burst_count {
        let t0 = get_micros();
        send_msg(&mut write, ClientMessage::TimeRequest { t0, seq: i }).await;

        // Wait for response
        if let Some(Ok(msg)) = read.next().await {
            if let Message::Binary(bytes) = msg {
                if let Ok(ServerMessage::TimeResponse { t0, t1, t2, seq: _ }) =
                    bincode::deserialize(&bytes)
                {
                    let t3 = get_micros();
                    let stats = ClockOffset::calculate(t0, t1, t2, t3);
                    println!(
                        "Sync #{}: RTT={}us Offset={}us",
                        i, stats.rtt, stats.offset
                    );
                    offset_stats.push(stats.offset);
                }
            }
        }
        tokio::time::sleep(tokio::time::Duration::from_millis(200)).await;
    }

    let avg_offset: i64 = offset_stats.iter().sum::<i64>() / offset_stats.len() as i64;
    println!("--- SYNC COMPLETE. AVG OFFSET: {}us ---", avg_offset);

    // 3. If we are "Host" (arg passed), send play command
    let args: Vec<String> = std::env::args().collect();
    if args.len() > 1 && args[1] == "host" {
        println!("Sending PlayRequest...");
        send_msg(
            &mut write,
            ClientMessage::PlayRequest {
                track_url: "http://example.com/track.mp3".into(),
                delay_ms: 3000,
            },
        )
        .await;
    }

    // 4. Listen loop
    println!("Listening for commands...");
    while let Some(Ok(msg)) = read.next().await {
        if let Message::Binary(bytes) = msg {
            if let Ok(server_msg) = bincode::deserialize::<ServerMessage>(&bytes) {
                match server_msg {
                    ServerMessage::PlayCommand {
                        start_at_server_time,
                        server_time_at_broadcast,
                        ..
                    } => {
                        let now_server = (get_micros() as i64 + avg_offset) as u64;
                        let wait_us = start_at_server_time.saturating_sub(now_server);
                        
                        println!(">>> PLAY COMMAND RECEIVED <<<");
                        println!("Server Broadcast Time: {}", server_time_at_broadcast);
                        println!("Target Server Time:    {}", start_at_server_time);
                        println!("Current Server Time:   {}", now_server);
                        println!("Time until play:       {}ms", wait_us / 1000);
                        
                        if wait_us > 0 {
                             tokio::time::sleep(tokio::time::Duration::from_micros(wait_us)).await;
                             println!("!!! PLAYING NOW !!!");
                        } else {
                             println!("!!! SKIPPED (LATE) !!!");
                        }
                    }
                    _ => {}
                }
            }
        }
    }
}

async fn send_msg<S>(write: &mut S, msg: ClientMessage)
where
    S: SinkExt<Message> + Unpin,
    S::Error: std::fmt::Debug,
{
    let bytes = bincode::serialize(&msg).unwrap();
    write.send(Message::Binary(bytes)).await.unwrap();
}

fn get_micros() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_micros() as u64
}
