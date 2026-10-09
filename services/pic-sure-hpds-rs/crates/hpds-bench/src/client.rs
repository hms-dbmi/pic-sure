use std::time::{Duration, Instant};

use reqwest::header::{CONTENT_TYPE, HeaderMap};
use serde_json::Value;

use crate::corpus::{CorpusEntry, Kind};
use crate::rng::Rng;

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
    /// A sync round trip.
    Sync(SyncOutcome),
    /// An async cycle.
    Async(AsyncOutcome),
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
            Self::Failed(_) => false,
        }
    }
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

impl Client {
    /// Builds a client for a base URL such as `http://host:8080/PIC-SURE`.
    ///
    /// # Errors
    ///
    /// Fails when the HTTP client cannot be constructed.
    pub fn new(base: &str, poll_timeout: Duration, pool_size: usize) -> crate::Result<Self> {
        let http = reqwest::Client::builder()
            .pool_max_idle_per_host(pool_size.max(1))
            .timeout(Duration::from_secs(300))
            .build()?;
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

    /// Runs one entry through its sync or async path. Transport errors become [`Outcome::Failed`].
    ///
    /// HPDS keys async results by a hash of the query text, so two in-flight submissions of the
    /// same query overwrite each other. Async requests therefore carry a `picsureId` derived from
    /// `nonce`; callers pass a value that is unique among concurrent requests to one server.
    pub async fn run(&self, entry: &CorpusEntry, nonce: u64) -> Outcome {
        let result = match entry.kind {
            Kind::Sync => self.run_sync(&entry.request).await.map(Outcome::Sync),
            Kind::Async => self
                .run_async(&with_picsure_id(&entry.request, nonce))
                .await
                .map(Outcome::Async),
        };
        result.unwrap_or_else(|e| Outcome::Failed(e.to_string()))
    }

    /// Sends a request body to `POST /v3/query/sync`.
    pub async fn run_sync(&self, request: &Value) -> Result<SyncOutcome, reqwest::Error> {
        let response = self
            .http
            .post(format!("{}/v3/query/sync", self.base))
            .json(request)
            .send()
            .await?;
        let status = response.status().as_u16();
        let content_type = normalize_content_type(response.headers());
        let has_query_metadata = response.headers().contains_key(QUERY_METADATA_HEADER);
        let bytes = response.bytes().await?;
        Ok(SyncOutcome {
            status,
            content_type,
            body: String::from_utf8_lossy(&bytes).into_owned(),
            has_query_metadata,
        })
    }

    /// Submits to `POST /v3/query`, polls `/status` until terminal, then fetches `/result`.
    pub async fn run_async(&self, request: &Value) -> Result<AsyncOutcome, reqwest::Error> {
        let submitted = self
            .http
            .post(format!("{}/v3/query", self.base))
            .json(request)
            .send()
            .await?;
        let mut outcome = AsyncOutcome {
            submit_status: submitted.status().as_u16(),
            ..AsyncOutcome::default()
        };
        let mut status: Value = submitted.json().await.unwrap_or(Value::Null);
        let started = Instant::now();
        let mut delay = Duration::from_millis(5);
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
                return Ok(outcome);
            }
            tokio::time::sleep(delay).await;
            delay = (delay * 2).min(Duration::from_millis(100));
            let id = status["resourceResultId"]
                .as_str()
                .unwrap_or_default()
                .to_owned();
            let polled = self
                .http
                .post(format!("{}/v3/query/{id}/status", self.base))
                .json(&empty_request())
                .send()
                .await?;
            let poll_code = polled.status().as_u16();
            if poll_code != 200 {
                outcome.status = None;
                outcome.resource_status = Some(format!("status poll returned HTTP {poll_code}"));
                return Ok(outcome);
            }
            status = polled.json().await.unwrap_or(Value::Null);
        }
        if outcome.resource_status.as_deref() == Some("SUCCESS") {
            let id = status["resourceResultId"].as_str().unwrap_or_default();
            let fetched = self
                .http
                .post(format!("{}/v3/query/{id}/result", self.base))
                .json(&empty_request())
                .send()
                .await?;
            outcome.result_status = Some(fetched.status().as_u16());
            outcome.result_content_type = normalize_content_type(fetched.headers());
            let bytes = fetched.bytes().await?;
            outcome.result_body = Some(String::from_utf8_lossy(&bytes).into_owned());
        }
        Ok(outcome)
    }
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
