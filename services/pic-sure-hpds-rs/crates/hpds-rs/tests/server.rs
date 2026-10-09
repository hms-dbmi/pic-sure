mod common;

use std::time::Duration;

use common::fixture;
use hpds_rs::{ServerTimeouts, router_with_body_timeout, serve};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

async fn start(
    fixture: &common::Fixture,
    timeouts: ServerTimeouts,
) -> (
    std::net::SocketAddr,
    tokio::sync::oneshot::Sender<()>,
    tokio::task::JoinHandle<()>,
) {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let (stop, stopped) = tokio::sync::oneshot::channel::<()>();
    let app = router_with_body_timeout(fixture.state.clone(), Duration::from_millis(200));
    let handle = tokio::spawn(async move {
        serve(
            listener,
            app,
            async {
                let _ = stopped.await;
            },
            timeouts,
        )
        .await
        .unwrap();
    });
    (addr, stop, handle)
}

fn quick() -> ServerTimeouts {
    ServerTimeouts {
        header_read: Duration::from_millis(200),
        shutdown_grace: Duration::from_millis(300),
        max_connections: 64,
    }
}

#[tokio::test]
async fn a_connection_that_stalls_in_the_headers_is_closed() {
    let f = fixture();
    let (addr, stop, handle) = start(&f, quick()).await;
    let mut stream = TcpStream::connect(addr).await.unwrap();
    stream
        .write_all(b"GET /actuator/health HTTP/1.1\r\nHost: x\r\n")
        .await
        .unwrap();
    let mut buffer = Vec::new();
    let read = tokio::time::timeout(Duration::from_secs(5), stream.read_to_end(&mut buffer)).await;
    assert!(read.is_ok(), "the server kept a stalled header open");
    drop(stop);
    handle.await.unwrap();
}

#[tokio::test]
async fn a_stalled_request_body_does_not_succeed() {
    let f = fixture();
    let (addr, stop, handle) = start(&f, quick()).await;
    let mut stream = TcpStream::connect(addr).await.unwrap();
    stream
        .write_all(
            b"POST /PIC-SURE/v3/query/sync HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{\"query\":",
        )
        .await
        .unwrap();
    let mut buffer = Vec::new();
    let read = tokio::time::timeout(Duration::from_secs(5), stream.read_to_end(&mut buffer)).await;
    assert!(read.is_ok(), "the server kept a stalled body open");
    let text = String::from_utf8_lossy(&buffer);
    assert!(!text.starts_with("HTTP/1.1 200"), "{text}");
    drop(stop);
    handle.await.unwrap();
}

#[tokio::test]
async fn shutdown_returns_within_the_grace_period_with_a_connection_open() {
    let f = fixture();
    let (addr, stop, handle) = start(
        &f,
        ServerTimeouts {
            header_read: Duration::from_secs(60),
            shutdown_grace: Duration::from_millis(300),
            max_connections: 64,
        },
    )
    .await;
    let mut idle = TcpStream::connect(addr).await.unwrap();
    idle.write_all(b"GET /actuator/health HTTP/1.1\r\nHost: x\r\n")
        .await
        .unwrap();
    tokio::time::sleep(Duration::from_millis(50)).await;
    stop.send(()).unwrap();
    tokio::time::timeout(Duration::from_secs(3), handle)
        .await
        .expect("shutdown overran the grace period")
        .unwrap();
}

#[tokio::test]
async fn a_body_trickled_past_the_total_deadline_is_answered_408() {
    let f = fixture();
    let (addr, stop, handle) = start(&f, quick()).await;
    let mut stream = TcpStream::connect(addr).await.unwrap();
    stream
        .write_all(
            b"POST /PIC-SURE/v3/query/sync HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n",
        )
        .await
        .unwrap();
    let started = std::time::Instant::now();
    let mut buffer = Vec::new();
    let mut chunk = [0u8; 256];
    while started.elapsed() < Duration::from_secs(5) {
        if stream.write_all(b"x").await.is_err() {
            break;
        }
        match tokio::time::timeout(Duration::from_millis(60), stream.read(&mut chunk)).await {
            Ok(Ok(0) | Err(_)) => break,
            Ok(Ok(n)) => buffer.extend_from_slice(&chunk[..n]),
            Err(_) => {}
        }
        if !buffer.is_empty() {
            break;
        }
    }
    let text = String::from_utf8_lossy(&buffer);
    assert!(text.starts_with("HTTP/1.1 408"), "{text}");
    assert!(started.elapsed() < Duration::from_secs(2));
    drop(stop);
    handle.await.unwrap();
}

#[tokio::test]
async fn an_oversize_body_is_answered_413() {
    let f = fixture();
    let (addr, stop, handle) = start(&f, quick()).await;
    let mut stream = TcpStream::connect(addr).await.unwrap();
    let body = vec![b'x'; hpds_rs::config::MAX_BODY_BYTES + 1];
    let head = format!(
        "POST /PIC-SURE/v3/query/sync HTTP/1.1\r\nHost: x\r\nContent-Length: {}\r\n\r\n",
        body.len()
    );
    stream.write_all(head.as_bytes()).await.unwrap();
    let _ = stream.write_all(&body).await;
    let mut buffer = Vec::new();
    let _ = tokio::time::timeout(Duration::from_secs(3), stream.read_to_end(&mut buffer)).await;
    let text = String::from_utf8_lossy(&buffer);
    assert!(text.starts_with("HTTP/1.1 413"), "{text}");
    drop(stop);
    handle.await.unwrap();
}

#[tokio::test]
async fn connections_past_the_cap_wait_until_a_slot_frees() {
    let f = fixture();
    let (addr, stop, handle) = start(
        &f,
        ServerTimeouts {
            header_read: Duration::from_secs(60),
            shutdown_grace: Duration::from_millis(300),
            max_connections: 2,
        },
    )
    .await;
    let first = TcpStream::connect(addr).await.unwrap();
    let mut second = TcpStream::connect(addr).await.unwrap();
    second
        .write_all(b"GET /actuator/health HTTP/1.1\r\nHost: x\r\n")
        .await
        .unwrap();
    tokio::time::sleep(Duration::from_millis(100)).await;
    let mut third = TcpStream::connect(addr).await.unwrap();
    third
        .write_all(b"GET /actuator/health HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
        .await
        .unwrap();
    let mut buffer = Vec::new();
    let waited =
        tokio::time::timeout(Duration::from_millis(400), third.read_to_end(&mut buffer)).await;
    assert!(
        waited.is_err(),
        "a third connection was served past the cap"
    );
    drop(first);
    tokio::time::timeout(Duration::from_secs(3), third.read_to_end(&mut buffer))
        .await
        .expect("the waiting connection was never served")
        .unwrap();
    assert!(String::from_utf8_lossy(&buffer).starts_with("HTTP/1.1 "));
    drop(stop);
    drop(second);
    handle.await.unwrap();
}

#[tokio::test]
async fn an_idle_kept_alive_connection_is_closed() {
    let f = fixture();
    let (addr, stop, handle) = start(&f, quick()).await;
    let mut stream = TcpStream::connect(addr).await.unwrap();
    stream
        .write_all(b"GET /actuator/health HTTP/1.1\r\nHost: x\r\n\r\n")
        .await
        .unwrap();
    let mut buffer = Vec::new();
    let read = tokio::time::timeout(Duration::from_secs(3), stream.read_to_end(&mut buffer)).await;
    assert!(read.is_ok(), "the server kept an idle connection open");
    drop(stop);
    handle.await.unwrap();
}
