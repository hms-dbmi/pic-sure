# PIC-SURE Audit Logging Service

A lightweight Java microservice that receives audit events via HTTP, enriches them with JWT claims and platform metadata, and outputs flat structured JSON log lines to stdout and a rolling log file. Designed to run as a Docker container on an internal network behind a reverse proxy.

## Overview

```
Calling Service ──POST /audit──> Audit Logging Service ──JSON──> stdout & Log File ──> Splunk / ELK / etc.
```

The service is intentionally simple: it receives, enriches, and emits. It does not store data, manage state, or verify JWT signatures. Log shipping to your SIEM is handled at the infrastructure layer.

## Quick Start

### Docker Compose (recommended)

```bash
docker compose up --build
```

This starts the service on port 8080 with a development API key. Test it:

```bash
# Health check (no auth required)
curl http://localhost:8080/health

# Send an audit event
curl -X POST http://localhost:8080/audit \
  -H "X-API-Key: dev-api-key-change-me" \
  -H "Content-Type: application/json" \
  -d '{"event_type": "QUERY", "action": "execute"}'
```

### Local Development

Requires Java 25 and Maven 3.9+.

```bash
# Build and run tests
mvn clean package

# Run directly
LOGGING_API_KEY=my-secret-key java -jar target/pic-sure-logging-3.0.0.jar
```

## API

### POST /audit

Accepts an audit event, enriches it with JWT claims and platform config, and writes a structured JSON log line to stdout and the audit log file.

**Headers:**

| Header | Required | Description |
|---|---|---|
| `X-API-Key` | Yes | Must match the configured `LOGGING_API_KEY` |
| `Content-Type` | No | Optional for valid JSON; `application/json` is recommended |
| `Authorization` | No | `Bearer <jwt>` -- claims are extracted and included in the log |
| `X-Request-Id` | No | Fallback request ID if not provided in the body |

**Request body:**

```json
{
  "event_type": "QUERY",
  "action": "execute",
  "client_type": "web",
  "request": {
    "request_id": "abc-123",
    "method": "POST",
    "url": "/api/query",
    "query_string": "limit=10",
    "src_ip": "192.168.1.1",
    "dest_ip": "10.0.0.5",
    "dest_port": 8443,
    "http_user_agent": "Mozilla/5.0",
    "http_content_type": "application/json",
    "status": 200,
    "bytes": 1024,
    "duration": 150,
    "referrer": "https://example.com"
  },
  "metadata": {
    "queryId": "b93bbc83-19f6-478b-9e97-1b6dbe165a00",
    "dataset": "phs000001"
  },
  "error": {
    "origin": "psama",
    "message": "Internal error"
  }
}
```

Only `event_type` is required. All other fields are optional. Unknown fields are silently ignored.

**Responses:**

| Status | Condition |
|---|---|
| `202 Accepted` | Event logged successfully |
| `400 Bad Request` | Invalid JSON or missing `event_type` |
| `401 Unauthorized` | Missing or invalid API key |
| `413 Content Too Large` | Request body exceeds 1 MiB (1,048,576 bytes) |
| `500 Internal Server Error` | Unexpected failure |

### POST /info

No authentication required. Returns the stable PIC-SURE resource descriptor with `200 OK`:

```json
{"id":"50585be4-e315-3a71-8874-c590d2ba12ec","name":"Logging Service","queryFormats":[]}
```

### GET /health

No authentication required. Returns `200 OK`:

```json
{"status": "healthy"}
```

## Output Format

Each audit event produces a single JSON line on both stdout and the rolling audit log file:

```json
{
  "_time": "2025-01-15T14:30:00.123456Z",
  "event_type": "QUERY",
  "action": "execute",
  "client_type": "web",
  "subject": "user123",
  "user_email": "user@example.com",
  "user_name": "Jane Doe",
  "roles": ["ADMIN", "USER"],
  "logged_in": true,
  "app": "pic-sure",
  "platform": "avillach-lab",
  "environment": "production",
  "hostname": "audit-svc-7f8b9c",
  "request_id": "abc-123",
  "method": "POST",
  "url": "/api/query",
  "query_string": "limit=10",
  "src_ip": "192.168.1.1",
  "dest_ip": "10.0.0.5",
  "dest_port": 8443,
  "http_user_agent": "Mozilla/5.0",
  "http_content_type": "application/json",
  "status": 200,
  "bytes": 1024,
  "duration": 150,
  "referrer": "https://example.com",
  "metadata": {"queryId": "b93bbc83-19f6-478b-9e97-1b6dbe165a00", "dataset": "phs000001"},
  "error": {"code": "500", "message": "Internal error"}
}
```

**Key behaviors:**
- Null/missing fields are omitted entirely
- `metadata` and `error` are only included when non-empty
- `logged_in` is always present (`true` with a valid JWT, `false` without)
- `_time` is generated server-side in ISO-8601 format
- Request fields are flattened to the top level (not nested under `request`)
- Operational/application logs go to stderr as plain text, keeping stdout clean for log shippers

## Configuration

All configuration is via environment variables.

| Variable | Required | Default | Description |
|---|---|---|---|
| `LOGGING_API_KEY` | **Yes** | -- | API key for `X-API-Key` authentication |
| `APP` | No | `unknown` | Application name included in log output |
| `PLATFORM` | No | `unknown` | Platform identifier included in log output |
| `ENVIRONMENT` | No | `unknown` | Deployment environment (e.g., `production`, `staging`) |
| `HOSTNAME` | No | System hostname | Container hostname (auto-set by Docker) |
| `PORT` | No | `8080` | HTTP listen port |
| `ALLOWED_ORIGIN` | No | `*` | CORS allowed origin (`*` for any) |
| `LOG_DIR` | No | `logs` | Directory for rolling log files (`audit.log`, `app.log`) |
| `JWT_CLAIM_MAPPING` | No | See below | JSON object mapping JWT claims to output field names |
| `PICSURE_ACTUATOR_EXPOSURE` | No | `none` | Comma-separated Actuator endpoint IDs to expose, such as `health,prometheus` |
| `PICSURE_ACTUATOR_DETAILS` | No | `never` | Actuator health detail policy (`never`, `when-authorized`, or `always`) |

**Startup validation:** The service fails fast with a clear error message if `LOGGING_API_KEY` is missing, `PORT` is not a valid integer in range 1-65535, or `JWT_CLAIM_MAPPING` is not valid JSON.

Actuator endpoints are disabled by default. Any endpoints enabled through `PICSURE_ACTUATOR_EXPOSURE` are unauthenticated because this service does not configure Spring Security; expose them only on a trusted network or behind an authenticating proxy.

## JWT Claim Extraction

The service decodes JWTs from the `Authorization: Bearer <token>` header to extract user context. **No signature verification is performed** -- the service trusts that the calling service or reverse proxy has already validated the token.

### Configurable Claim Mapping

The `JWT_CLAIM_MAPPING` environment variable controls which JWT claims are extracted and what they're named in the output. It's a JSON object where keys are JWT claim names and values are output field names:

```bash
JWT_CLAIM_MAPPING='{"sub":"subject","email":"user_email","name":"user_name","roles":"roles"}'
```

**Default mapping** (used when `JWT_CLAIM_MAPPING` is not set):

| JWT Claim | Output Field |
|---|---|
| `sub` | `subject` |
| `email` | `user_email` |
| `name` | `user_name` |
| `userid` | `user_id` |
| `preferred_username` | `preferred_username` |
| `org` | `user_org` |
| `country_name` | `user_country_name` |
| `nih_ico` | `nih_ico` |
| `eRA_commons_id` | `eRA_commons_id` |
| `user_permission_group` | `user_permission_group` |
| `uuid` | `uuid` |
| `roles` | `roles` |
| `idp` | `user_id_provider` |
| `cadr_name` | `cadr_name` |

There is no `session_id` or `logged_in` entry: `session_id` is read from the request
body, and `logged_in` is emitted unconditionally by `JwtDecodeService`.

### Type Handling

- **`roles`** -- If the JWT claim is a JSON array, it is preserved as a list (e.g., `["ADMIN", "USER"]`), not flattened to a comma-separated string
- **`logged_in`** -- Boolean type is preserved
- **All other claims** -- Extracted as strings
- **Missing claims** -- Omitted from output (no nulls)

### No JWT Present

When no `Authorization` header is provided, the header is blank, or only the `Bearer` prefix is present, the output includes `"logged_in": false` and no other user fields without logging a warning. Malformed or oversized tokens also degrade to `"logged_in": false`, but log a warning to stderr. JWT decoding never causes the audit request to fail.

## Request ID

The service does not generate request IDs. It accepts them from:

1. The `request.request_id` field in the POST body (takes priority)
2. The `X-Request-Id` HTTP header (fallback)

If neither is present, the field is omitted from the output. Request IDs are expected to be generated by the reverse proxy or calling service.

## Architecture

```
PIC-SURE-Logging/
├── pom.xml                          # Maven build, Java 25, Spring Boot 3.5.16, executable jar
├── Dockerfile                       # Single-stage: amazoncorretto:25 runtime
├── docker-compose.yml               # Local development
└── src/main/java/edu/harvard/dbmi/avillach/logging/
    ├── LoggingServiceApplication.java  # @SpringBootApplication entry point
    ├── config/
    │   ├── LoggingProperties.java      # @ConfigurationProperties, fail-fast validation
    │   ├── JwtClaimMappingConverter.java # JWT_CLAIM_MAPPING parsing + default map
    │   ├── BeanConfig.java             # Service beans
    │   ├── WebConfig.java              # CORS
    │   └── FilterConfig.java           # Filter registrations and ordering
    ├── filter/
    │   ├── ApiKeyAuthFilter.java       # Constant-time API key comparison -> 401
    │   └── RequestSizeLimitFilter.java # 1MB body cap -> 413
    ├── web/
    │   ├── AuditController.java        # POST /audit -> 202
    │   ├── HealthController.java       # GET /health -> 200/503
    │   ├── InfoController.java         # POST /info -> 200
    │   └── ApiExceptionHandler.java    # 400 / 500
    ├── model/                          # AuditEvent, RequestInfo, InfoResponse
    └── service/
        ├── AuditLogService.java        # Core logic: assemble fields, emit JSON
        ├── JwtDecodeService.java       # JWT decode with configurable claim mapping
        └── ReadinessState.java         # Readiness flag driven by lifecycle events
```

**Design decisions:**
- Spring Boot 3.5.16, no Spring Security -- the API key is a servlet filter
- Actuator is off by default (`PICSURE_ACTUATOR_EXPOSURE=none`); opted-in endpoints are unauthenticated
- No database or persistent state -- pure log enrichment and forwarding
- JWT decode-only (no verification) -- trusts upstream authentication
- Constant-time API key comparison via `MessageDigest.isEqual()` to prevent timing attacks
- Logging failures never cause HTTP errors -- catch-all wraps the entire log assembly
- `spring.main.banner-mode: off` keeps stdout a pure JSON stream for log shippers

## Logging Architecture

The service uses two separate log channels, each writing to both a console stream and an async rolling file:

| Channel | Console | File | Format | Content |
|---|---|---|---|---|
| `AUDIT` logger | stdout | `${LOG_DIR}/audit.log` | Structured JSON (LogstashEncoder) | Audit event lines |
| Root logger | stderr | `${LOG_DIR}/app.log` | Plain text | Application/operational logs |

File appenders use `SizeAndTimeBasedRollingPolicy` (rotates daily and at 50 MB, 30-day retention, 1 GB total cap for audit / 500 MB for app) and are wrapped in async appenders (`neverBlock=true`, `discardingThreshold=0`) so file I/O never blocks HTTP threads.

This separation allows log shippers to capture clean JSON from stdout while operational noise goes to stderr. The rolling files provide a local fallback when stdout-based shipping is unavailable.

## Docker

### Building

```bash
docker build -t pic-sure-logging .
```

The Dockerfile is single-stage: it copies the jar that the Maven reactor has already
built into `target/`. The base image is `amazoncorretto:25`, matching
`pic-sure-gateway` and `pic-sure-operations-service`.

### Running

```bash
docker run -d \
  -p 8080:8080 \
  -e LOGGING_API_KEY=your-secret-key \
  -e APP=pic-sure \
  -e PLATFORM=avillach-lab \
  -e ENVIRONMENT=production \
  pic-sure-logging
```

## Log Shipping

The service writes JSON to both stdout and a rolling file (`${LOG_DIR}/audit.log`). Shipping logs to your SIEM is an infrastructure concern. Common approaches:

### Docker Splunk Logging Driver

```bash
docker run -d \
  --log-driver=splunk \
  --log-opt splunk-token=YOUR_HEC_TOKEN \
  --log-opt splunk-url=https://your-splunk:8088 \
  --log-opt splunk-format=json \
  --log-opt splunk-sourcetype=audit:picsure \
  -e LOGGING_API_KEY=your-key \
  pic-sure-logging
```

### OpenTelemetry Collector

Use a `filelog` receiver to tail Docker JSON log files and forward to Splunk HEC, Elasticsearch, or any OTLP-compatible backend.

### Fluentd / Fluent Bit

Sidecar that reads Docker JSON log files and forwards to your destination. Well-documented for both Docker and Kubernetes deployments.

### Splunk Compatibility

- **`_time`** -- Recognized by Splunk as the event timestamp (configure `TIME_FORMAT = %Y-%m-%dT%H:%M:%S.%3NZ`)
- **Nested objects** (`metadata`, `error`) -- Auto-extracted via dot notation (e.g., `metadata.query_id`)
- **`hostname`** -- Does not conflict with Splunk's reserved `host` field
- **Array fields** (`roles`) -- Treated as multivalued fields, searchable with `roles=ADMIN`
- **Sourcetype** -- Use `_json` for automatic field extraction or a custom `audit:picsure`

## Development

### Prerequisites

- Java 25
- Maven 3.9+
- Docker (for container builds)

### Running Tests

```bash
mvn test
```

The test suite includes:
- **Unit tests** -- Configuration, service, and filter tests (`LoggingPropertiesTest`, `JwtDecodeServiceTest`, `AuditLogServiceTest`, `ApiKeyAuthFilterTest`, `RequestSizeLimitFilterTest`)
- **Integration tests** -- Controller tests and application startup (`AuditControllerTest`, `HealthControllerTest`, `InfoControllerTest`, `LoggingServiceApplicationTest`)

Tests use a `ListAppender` on the `AUDIT` logger to capture and assert on structured log output, and a `TestJwtBuilder` helper to create JWTs signed with a test secret.

### Building the Fat JAR

```bash
mvn clean package
```

Produces `target/pic-sure-logging-3.0.0.jar` (~25 MB) containing all dependencies.

## Error Handling

| Layer | Error | Behavior |
|---|---|---|
| Startup | Missing `LOGGING_API_KEY` | Exit with clear error message |
| Startup | Invalid `PORT` | Exit with clear error message |
| Startup | Invalid `JWT_CLAIM_MAPPING` | Exit with clear error message |
| HTTP | Missing/wrong API key | `401 Unauthorized` |
| HTTP | Malformed JSON body | `400 Bad Request` with detail |
| HTTP | Missing `event_type` | `400 Bad Request` |
| HTTP | Authenticated body over 1 MiB | `413 Content Too Large` |
| JWT | Missing/blank token or bare `Bearer` prefix | No warning, `logged_in: false` in output |
| JWT | Malformed or oversized token | Warning to stderr, `logged_in: false` in output |
| JWT | Missing individual claim | Omit that field |
| Logging | Any exception during log assembly | Catch-all logs error to stderr, HTTP still returns `202` |
| Global | Unexpected exception | `500 Internal Server Error` |

## Deployment Requirements

This service is designed to run behind a reverse proxy on an internal network. It does **not** handle TLS or JWT verification itself.

| Requirement | Responsibility | Notes |
|---|---|---|
| **TLS termination** | Reverse proxy (e.g., Nginx, Traefik, ALB) | Service listens on plain HTTP |
| **JWT signature verification** | Upstream service or API gateway | Service decodes JWTs but does not verify signatures |
| **Request ID generation** | Reverse proxy or calling service | Service accepts `X-Request-Id` but does not generate IDs |

### Resource Recommendations

| Resource | Minimum | Recommended |
|---|---|---|
| Memory | 256 MB | 512 MB |
| CPU | 0.25 vCPU | 1 vCPU |
| Disk (logs) | 2 GB | 5 GB |

The `docker-compose.yml` binds the port to `127.0.0.1` by default, which is appropriate when the service is only accessed through a local reverse proxy. Adjust the bind address if the reverse proxy runs on a separate host.

## Security Considerations

- **API key auth** -- All `/audit` requests require a valid `X-API-Key` header. `/health`, `/info`, and any opted-in Actuator endpoints are unauthenticated.
- **Constant-time comparison** -- API key validation uses `MessageDigest.isEqual()` to prevent timing-based attacks.
- **No JWT verification** -- This service does not verify JWT signatures. It is designed to run on an internal network where tokens have already been validated by an upstream service or API gateway.
- **Container user** -- The image runs as root, consistent with the other PIC-SURE
  services. Container hardening is tracked as a separate, repo-wide change.
- **Request size limit** -- HTTP request bodies are capped at 1 MB.
- **No secrets in logs** -- The raw JWT token is never written to the audit log; only extracted claims appear.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for the full text.
