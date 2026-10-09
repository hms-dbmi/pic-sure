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
