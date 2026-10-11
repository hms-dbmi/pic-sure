use std::time::{Duration, Instant};

use reqwest::header::{CONTENT_TYPE, HeaderMap};
use serde_json::Value;

use crate::corpus::{CorpusEntry, Kind};
use crate::rng::Rng;

/// Id used to probe how a server answers for a result it never produced.
pub const UNKNOWN_QUERY_ID: &str = "00000000-0000-4000-8000-00000000dead";

/// Header HPDS sets on every successful sync response.
pub const QUERY_METADATA_HEADER: &str = "queryMetadata";

/// Everything observed from one sync request.
#[derive(Debug, Clone, PartialEq)]
pub struct SyncOutcome {
    /// HTTP status code.
    pub status: u16,
    /// Content type with the charset parameter removed.
    pub content_type: Option<String>,
    /// Response body decoded as UTF-8.
    pub body: String,
    /// Whether the `queryMetadata` header was present.
    pub has_query_metadata: bool,
}

/// Everything observed from one submit, poll and fetch cycle.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct AsyncOutcome {
    /// HTTP status of the submit call.
    pub submit_status: u16,
    /// Terminal `status` field of the query status (`AVAILABLE`, `ERROR`, ...).
    pub status: Option<String>,
    /// Terminal `resourceStatus` field (`SUCCESS`, `ERROR`, ...).
    pub resource_status: Option<String>,
    /// HTTP status of the result fetch, when one was made.
    pub result_status: Option<u16>,
    /// Content type of the result with the charset parameter removed.
    pub result_content_type: Option<String>,
    /// Result body decoded as UTF-8.
    pub result_body: Option<String>,
    /// Whether polling gave up before a terminal state.
    pub timed_out: bool,
}

/// Result of running one corpus entry against one server.
#[derive(Debug, Clone, PartialEq)]
pub enum Outcome {
    /// A sync round trip, a search, or an unknown-id probe.
    Sync(SyncOutcome),
    /// An async cycle.
    Async(AsyncOutcome),
    /// The server did not answer within the timeout.
    TimedOut,
    /// The request failed before a response was read.
    Failed(String),
}

impl Outcome {
    /// Whether the server answered the way a healthy deployment does for a valid request.
    pub fn is_success(&self) -> bool {
        match self {
            Self::Sync(s) => s.status == 200,
            Self::Async(a) => {
                a.resource_status.as_deref() == Some("SUCCESS") && a.result_status == Some(200)
            }
            Self::TimedOut | Self::Failed(_) => false,
        }
    }

    /// Whether the server failed to answer within the timeout.
    pub fn is_timeout(&self) -> bool {
        match self {
            Self::Async(a) => a.timed_out,
            Self::TimedOut => true,
            Self::Sync(_) | Self::Failed(_) => false,
        }
    }

    /// The HTTP status that characterizes the outcome: the sync status, the submit status when
    /// an async submit was refused, otherwise the result status. Zero when no response came.
    pub fn status_code(&self) -> u16 {
        match self {
            Self::Sync(s) => s.status,
            Self::Async(a) if a.submit_status != 200 => a.submit_status,
            Self::Async(a) => a.result_status.unwrap_or(a.submit_status),
            Self::TimedOut | Self::Failed(_) => 0,
        }
    }
}

/// How the async status poll is paced.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PollStrategy {
    /// Start at 5 ms and double up to 100 ms. Used by compare, which values low request volume.
    Backoff,
    /// Poll at a constant interval. Used by load so poll latency is the same for every request.
    Fixed(Duration),
}

/// How a response body is consumed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum BodyMode {
    Buffer,
    Count,
}

/// Timing and size of one request as load mode sees it.
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct Measured {
    /// Whether the server answered the way a healthy deployment does.
    pub success: bool,
    /// Whether the request hit the timeout.
    pub timed_out: bool,
    /// Submit to terminal status for async entries; request to response headers for the rest.
    pub service: Duration,
    /// Time spent fetching and draining the result body, when one was fetched.
    pub download: Option<Duration>,
    /// Response body bytes read.
    pub bytes: u64,
}

/// HTTP driver for one server, with a shared keep-alive connection pool.
#[derive(Debug, Clone)]
pub struct Client {
    http: reqwest::Client,
    base: String,
    poll_timeout: Duration,
}

/// Strips the charset parameter and lowercases the media type.
pub fn normalize_content_type(headers: &HeaderMap) -> Option<String> {
    let raw = headers.get(CONTENT_TYPE)?.to_str().ok()?;
    let mut parts = raw.split(';').map(str::trim);
    let media = parts.next()?.to_ascii_lowercase();
    let rest: Vec<String> = parts
        .filter(|p| !p.to_ascii_lowercase().starts_with("charset="))
        .map(str::to_owned)
        .collect();
    Some(if rest.is_empty() {
        media
    } else {
        format!("{media}; {}", rest.join("; "))
    })
}

struct Cycle<T> {
    outcome: T,
    service: Duration,
    download: Option<Duration>,
    bytes: u64,
}

enum Payload<'a> {
    Json(&'a Value),
    Raw(&'a str),
}

/// Builds the HTTP client, trusting the system certificate roots or, when `system_roots` is
/// false, only the roots compiled into the binary.
///
/// # Errors
///
/// Fails when the TLS backend cannot be initialised with the chosen roots.
fn build_http(
    max_idle: usize,
    timeout: Duration,
    system_roots: bool,
    headers: &HeaderMap,
) -> Result<reqwest::Client, reqwest::Error> {
    let builder = reqwest::Client::builder()
        .pool_max_idle_per_host(max_idle.max(1))
        .timeout(timeout)
        .default_headers(headers.clone());
    let builder = if system_roots {
        builder
    } else {
        builder.tls_certs_only(
            webpki_root_certs::TLS_SERVER_ROOT_CERTS
                .iter()
                .filter_map(|der| reqwest::Certificate::from_der(der).ok()),
        )
    };
    builder.build()
}

impl Client {
    /// Builds a client for a base URL such as `http://host:8080/PIC-SURE`.
    ///
    /// `max_idle` caps the idle keep-alive connections kept per host, and `poll_timeout` bounds
    /// both the wait for an async query to finish and every sync request.
    ///
    /// # Errors
    ///
    /// Fails when the HTTP client cannot be constructed. The system certificate roots are tried
    /// first, and the roots bundled in the binary are used when the system has none, so plain
    /// HTTP targets work in images without CA certificates.
    pub fn new(base: &str, poll_timeout: Duration, max_idle: usize) -> crate::Result<Self> {
        Self::with_headers(base, poll_timeout, max_idle, &HeaderMap::new())
    }

    /// Builds a client as [`Client::new`] does that also sends `headers` on every request, for
    /// example the `Accept-Encoding` a particular caller sends. Bodies are never decoded, so
    /// byte counts are what crossed the wire.
    ///
    /// # Errors
    ///
    /// Fails when the HTTP client cannot be constructed.
    pub fn with_headers(
        base: &str,
        poll_timeout: Duration,
        max_idle: usize,
        headers: &HeaderMap,
    ) -> crate::Result<Self> {
        let timeout = Duration::from_secs(300).max(poll_timeout);
        let http = build_http(max_idle, timeout, true, headers)
            .or_else(|_| build_http(max_idle, timeout, false, headers))?;
        Ok(Self {
            http,
            base: base.trim_end_matches('/').to_owned(),
            poll_timeout,
        })
    }

    /// The base URL this client talks to.
    pub fn base(&self) -> &str {
        &self.base
    }

    fn post(&self, path: &str, payload: &Payload<'_>) -> reqwest::RequestBuilder {
        let request = self.http.post(format!("{}/v3/{path}", self.base));
        match payload {
            Payload::Json(value) => request.json(value),
            Payload::Raw(text) => request
                .header(CONTENT_TYPE, "application/json")
                .body((*text).to_owned()),
        }
    }

    /// Runs one entry through its path and buffers the bodies. Timeouts become
    /// [`Outcome::TimedOut`], other transport errors [`Outcome::Failed`].
    ///
    /// HPDS keys async results by a hash of the query text, so two in-flight submissions of the
    /// same query overwrite each other. Async requests therefore carry a `picsureId` derived from
    /// `nonce`; callers pass a value that is unique among concurrent requests to one server.
    pub async fn run(&self, entry: &CorpusEntry, nonce: u64) -> Outcome {
        let result = match entry.kind {
            Kind::Async => {
                let request = with_picsure_id(&entry.request, nonce);
                let payload = payload_for(entry, &request);
                self.async_cycle(&payload, PollStrategy::Backoff, BodyMode::Buffer)
                    .await
                    .map(|c| Outcome::Async(c.outcome))
            }
            _ => self
                .sync_cycle(entry, BodyMode::Buffer)
                .await
                .map(|c| Outcome::Sync(c.outcome)),
        };
        result.unwrap_or_else(|e| {
            if e.is_timeout() {
                Outcome::TimedOut
            } else {
                Outcome::Failed(e.to_string())
            }
        })
    }

    /// Runs one entry for load mode: bodies are streamed and counted, not kept, and async
    /// polling uses `poll`. Timing is split into service time and download time.
    pub async fn run_measured(
        &self,
        entry: &CorpusEntry,
        nonce: u64,
        poll: PollStrategy,
    ) -> Measured {
        let began = Instant::now();
        let result = match entry.kind {
            Kind::Async => {
                let request = with_picsure_id(&entry.request, nonce);
                let payload = payload_for(entry, &request);
                self.async_cycle(&payload, poll, BodyMode::Count)
                    .await
                    .map(|c| Measured {
                        success: Outcome::Async(c.outcome.clone()).is_success(),
                        timed_out: c.outcome.timed_out,
                        service: c.service,
                        download: c.download,
                        bytes: c.bytes,
                    })
            }
            _ => self
                .sync_cycle(entry, BodyMode::Count)
                .await
                .map(|c| Measured {
                    success: c.outcome.status == 200,
                    timed_out: false,
                    service: c.service,
                    download: c.download,
                    bytes: c.bytes,
                }),
        };
        result.unwrap_or_else(|e| Measured {
            timed_out: e.is_timeout(),
            service: began.elapsed(),
            ..Measured::default()
        })
    }

    async fn sync_cycle(
        &self,
        entry: &CorpusEntry,
        mode: BodyMode,
    ) -> Result<Cycle<SyncOutcome>, reqwest::Error> {
        let payload = payload_for(entry, &entry.request);
        let empty = empty_request();
        let started = Instant::now();
        let request = match entry.kind {
            Kind::Search => self.post("search", &payload),
            Kind::UnknownStatus => self.post(
                &format!("query/{UNKNOWN_QUERY_ID}/status"),
                &Payload::Json(&empty),
            ),
            Kind::UnknownResult => self.post(
                &format!("query/{UNKNOWN_QUERY_ID}/result"),
                &Payload::Json(&empty),
            ),
            Kind::Sync | Kind::Async => self.post("query/sync", &payload),
        };
        let response = request.timeout(self.poll_timeout).send().await?;
        let service = started.elapsed();
        let status = response.status().as_u16();
        let content_type = normalize_content_type(response.headers());
        let has_query_metadata = response.headers().contains_key(QUERY_METADATA_HEADER);
        let drain_started = Instant::now();
        let (body, bytes) = read_body(response, mode).await?;
        Ok(Cycle {
            outcome: SyncOutcome {
                status,
                content_type,
                body,
                has_query_metadata,
            },
            service,
            download: Some(drain_started.elapsed()),
            bytes,
        })
    }

    async fn async_cycle(
        &self,
        payload: &Payload<'_>,
        poll: PollStrategy,
        mode: BodyMode,
    ) -> Result<Cycle<AsyncOutcome>, reqwest::Error> {
        let started = Instant::now();
        let submitted = self.post("query", payload).send().await?;
        let mut outcome = AsyncOutcome {
            submit_status: submitted.status().as_u16(),
            ..AsyncOutcome::default()
        };
        let mut status: Value = submitted.json().await.unwrap_or(Value::Null);
        let mut delay = match poll {
            PollStrategy::Backoff => Duration::from_millis(5),
            PollStrategy::Fixed(interval) => interval,
        };
        let empty = empty_request();
        loop {
            let resource_status = status["resourceStatus"].as_str().map(str::to_owned);
            let terminal = matches!(resource_status.as_deref(), Some("SUCCESS" | "ERROR"))
                || outcome.submit_status != 200
                || status["resourceResultId"].as_str().is_none();
            outcome.status = status["status"].as_str().map(str::to_owned);
            outcome.resource_status = resource_status;
            if terminal {
                break;
            }
            if started.elapsed() > self.poll_timeout {
                outcome.timed_out = true;
                return Ok(Cycle {
                    outcome,
                    service: started.elapsed(),
                    download: None,
                    bytes: 0,
                });
            }
            tokio::time::sleep(delay).await;
            if poll == PollStrategy::Backoff {
                delay = (delay * 2).min(Duration::from_millis(100));
            }
            let id = status["resourceResultId"]
                .as_str()
                .unwrap_or_default()
                .to_owned();
            let polled = self
                .post(&format!("query/{id}/status"), &Payload::Json(&empty))
                .send()
                .await?;
            let poll_code = polled.status().as_u16();
            if poll_code != 200 {
                outcome.status = None;
                outcome.resource_status = Some(format!("status poll returned HTTP {poll_code}"));
                return Ok(Cycle {
                    outcome,
                    service: started.elapsed(),
                    download: None,
                    bytes: 0,
                });
            }
            status = polled.json().await.unwrap_or(Value::Null);
        }
        let service = started.elapsed();
        let mut download = None;
        let mut bytes = 0;
        if outcome.resource_status.as_deref() == Some("SUCCESS") {
            let id = status["resourceResultId"].as_str().unwrap_or_default();
            let fetch_started = Instant::now();
            let fetched = self
                .post(&format!("query/{id}/result"), &Payload::Json(&empty))
                .send()
                .await?;
            outcome.result_status = Some(fetched.status().as_u16());
            outcome.result_content_type = normalize_content_type(fetched.headers());
            let (body, count) = read_body(fetched, mode).await?;
            if mode == BodyMode::Buffer {
                outcome.result_body = Some(body);
            }
            bytes = count;
            download = Some(fetch_started.elapsed());
        }
        Ok(Cycle {
            outcome,
            service,
            download,
            bytes,
        })
    }
}

fn payload_for<'a>(entry: &'a CorpusEntry, request: &'a Value) -> Payload<'a> {
    match &entry.raw_body {
        Some(text) => Payload::Raw(text),
        None => Payload::Json(request),
    }
}

async fn read_body(
    mut response: reqwest::Response,
    mode: BodyMode,
) -> Result<(String, u64), reqwest::Error> {
    let mut kept = Vec::new();
    let mut total = 0u64;
    while let Some(chunk) = response.chunk().await? {
        total += chunk.len() as u64;
        if mode == BodyMode::Buffer {
            kept.extend_from_slice(&chunk);
        }
    }
    Ok((String::from_utf8_lossy(&kept).into_owned(), total))
}

/// Returns a copy of an envelope whose query carries a `picsureId` UUID derived from `nonce`.
pub fn with_picsure_id(request: &Value, nonce: u64) -> Value {
    let mut rng = Rng::new(nonce);
    let (high, low) = (rng.next_u64(), rng.next_u64());
    let id = format!(
        "{:08x}-{:04x}-4{:03x}-{:04x}-{:012x}",
        (high >> 32) as u32,
        ((high >> 16) & 0xffff) as u16,
        (high & 0xfff) as u16,
        0x8000 | ((low >> 48) & 0x3fff) as u16,
        low & 0xffff_ffff_ffff,
    );
    let mut out = request.clone();
    if let Some(query) = out.get_mut("query").and_then(Value::as_object_mut) {
        query.insert("picsureId".into(), Value::String(id));
    }
    out
}

fn empty_request() -> Value {
    serde_json::json!({"query": null, "resourceUUID": null})
}

#[cfg(test)]
mod tests {
    use super::*;
    use reqwest::header::HeaderValue;

    #[test]
    fn client_builds_with_bundled_roots_only() {
        let timeout = Duration::from_secs(5);
        build_http(2, timeout, false, &HeaderMap::new()).expect("bundled roots build");
        Client::new("http://example.invalid/PIC-SURE", timeout, 2).expect("client");
    }

    #[test]
    fn picsure_id_is_a_distinct_valid_uuid_per_nonce() {
        let request =
            serde_json::json!({"query": {"expectedResultType": "PATIENTS"}, "resourceUUID": null});
        let a = with_picsure_id(&request, 1);
        let b = with_picsure_id(&request, 2);
        let id = a["query"]["picsureId"].as_str().unwrap();
        assert_eq!(id.len(), 36);
        assert_eq!(id.as_bytes()[14], b'4');
        assert_ne!(a, b);
        assert_eq!(a, with_picsure_id(&request, 1));
        assert!(request["query"].get("picsureId").is_none());
    }

    #[test]
    fn content_type_drops_charset_only() {
        let mut headers = HeaderMap::new();
        headers.insert(
            CONTENT_TYPE,
            HeaderValue::from_static("Text/Plain;charset=UTF-8"),
        );
        assert_eq!(
            normalize_content_type(&headers).as_deref(),
            Some("text/plain")
        );
        headers.insert(
            CONTENT_TYPE,
            HeaderValue::from_static("application/json; charset=utf-8; v=1"),
        );
        assert_eq!(
            normalize_content_type(&headers).as_deref(),
            Some("application/json; v=1")
        );
        assert_eq!(normalize_content_type(&HeaderMap::new()), None);
    }
}
