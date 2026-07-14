# PIC-SURE Logging Client

Lightweight Java client library for sending structured audit events to the [PIC-SURE Logging](https://github.com/hms-dbmi/PIC-SURE-Logging) service. Used by PIC-SURE platform components (API, Auth, HPDS, UI, etc.) to emit query, login, access, and error events to a centralized logging service.

- **Java 11+** compatible (works with WildFly 17 through Spring Boot 3.x)
- **Minimal dependencies** — uses `java.net.http.HttpClient` (built into JDK 11+), Jackson, and SLF4J
- **Fire-and-forget** — async sends that never throw or block the caller
- **Thread-safe** — create one instance and share it across your application
- **No-op mode** — gracefully degrades when the logging service isn't configured
- **Environment-based factory** — auto-configures from `LOGGING_SERVICE_URL` and `LOGGING_API_KEY`
- **Secret-safe logging** — exception messages are sanitized to avoid leaking API keys in logs

## Installation

Add the GitHub Packages repository and dependency to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>github</id>
        <name>GitHub HMS-DBMI Apache Maven Packages</name>
        <url>https://maven.pkg.github.com/hms-dbmi/pic-sure-logging-client</url>
    </repository>
</repositories>

<dependency>
    <groupId>edu.harvard.dbmi.avillach</groupId>
    <artifactId>pic-sure-logging-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Quick Start

### Using the factory (recommended)

Set environment variables and let `LoggingClientFactory` handle configuration:

```bash
export LOGGING_SERVICE_URL=http://pic-sure-logging:80
export LOGGING_API_KEY=your-api-key
```

```java
// Returns a configured client, or no-op if env vars are missing
LoggingClient client = LoggingClientFactory.create("api");

client.send(LoggingEvent.builder("QUERY").action("execute").build());
```

### Manual configuration

```java
import edu.harvard.dbmi.avillach.logging.*;

// 1. Configure (once at startup)
LoggingClientConfig config = LoggingClientConfig.builder("http://pic-sure-logging:80", apiKey)
    .clientType("api")       // default client_type for all events
    .build();

// 2. Create client (thread-safe, share everywhere)
LoggingClient client = new LoggingClient(config);

// 3. Send events
client.send(LoggingEvent.builder("QUERY").action("execute").build());
```

## Usage Examples

### Simple event

```java
client.send(LoggingEvent.builder("LOGIN").action("success").build());
```

### With request context

```java
client.send(LoggingEvent.builder("QUERY")
    .action("execute")
    .request(RequestInfo.builder()
        .method("POST")
        .url("/query/sync")
        .srcIp(httpRequest.getRemoteAddr())
        .status(200)
        .duration(elapsed)
        .build())
    .metadata(Map.of("resourceId", resourceUUID.toString()))
    .build());
```

### With JWT passthrough and request correlation

The logging service can extract user claims from a JWT. Pass the Authorization header through so it can attribute events to users:

```java
client.send(
    LoggingEvent.builder("QUERY")
        .action("execute")
        .request(RequestInfo.builder()
            .method("POST")
            .url("/query/sync")
            .srcIp(httpRequest.getRemoteAddr())
            .status(200)
            .duration(elapsed)
            .build())
        .build(),
    httpRequest.getHeader("Authorization"),   // JWT passthrough
    httpRequest.getHeader("X-Request-Id")     // request correlation
);
```

### Error events

```java
client.send(LoggingEvent.builder("QUERY")
    .action("execute")
    .request(RequestInfo.builder()
        .method("POST")
        .url("/query/sync")
        .status(500)
        .duration(elapsed)
        .build())
    .error(Map.of(
        "type", e.getClass().getSimpleName(),
        "message", e.getMessage()
    ))
    .build());
```

### No-op mode

When the logging service isn't available, use the no-op client. It silently discards all events so calling code doesn't need null checks or conditionals:

```java
LoggingClient client = LoggingClient.noOp();
client.isEnabled(); // false
client.send(...);   // does nothing
```

## Event Model

### LoggingEvent fields

| Field | Type | Required | Description |
|---|---|---|---|
| `event_type` | String | Yes | Event category (e.g. `QUERY`, `LOGIN`, `ACCESS`, `ERROR`) |
| `action` | String | No | Specific action (e.g. `execute`, `attempt`, `success`, `failure`, `read`) |
| `client_type` | String | No | Source component (e.g. `api`, `auth`, `hpds`, `ui`). Falls back to config default. |
| `request` | RequestInfo | No | HTTP request details (see below) |
| `metadata` | Map<String, Object> | No | Arbitrary key-value pairs (max 50 keys) |
| `error` | Map<String, Object> | No | Error details (max 20 keys) |

### RequestInfo fields

| Field | Type | Description |
|---|---|---|
| `request_id` | String | Correlation ID |
| `method` | String | HTTP method |
| `url` | String | Request URL/path |
| `query_string` | String | Query parameters |
| `src_ip` | String | Client IP address |
| `dest_ip` | String | Destination IP |
| `dest_port` | Integer | Destination port |
| `http_user_agent` | String | User-Agent header |
| `http_content_type` | String | Content-Type header |
| `status` | Integer | HTTP response status |
| `bytes` | Long | Response size in bytes |
| `duration` | Long | Request duration in ms |
| `referrer` | String | Referrer URL |

### JSON format

Events are serialized with snake_case field names. Null fields are omitted.

```json
{
  "event_type": "QUERY",
  "action": "execute",
  "client_type": "api",
  "request": {
    "method": "POST",
    "url": "/query/sync",
    "src_ip": "192.168.1.1",
    "status": 200,
    "duration": 150
  },
  "metadata": {
    "resourceId": "uuid-123"
  }
}
```

### Graceful degradation

For consumers where the logging-client JAR may not be on the classpath:

```java
LoggingClient client;
try {
    client = LoggingClientFactory.create("api");
} catch (NoClassDefFoundError e) {
    client = null;
}
```

## Integration

### pic-sure (Java 11, CDI / WildFly)

```java
@ApplicationScoped
public class LoggingClientProducer {

    @Produces
    @ApplicationScoped
    public LoggingClient loggingClient() {
        return LoggingClientFactory.create("api");
    }
}
```

Then inject it anywhere:

```java
@ApplicationScoped
public class PicsureQueryService {

    @Inject
    LoggingClient loggingClient;

    public Response query(QueryRequest queryRequest, HttpServletRequest httpRequest) {
        long start = System.currentTimeMillis();
        // ... execute query ...
        long duration = System.currentTimeMillis() - start;

        loggingClient.send(
            LoggingEvent.builder("QUERY")
                .action("execute")
                .request(RequestInfo.builder()
                    .method("POST")
                    .url(httpRequest.getRequestURI())
                    .srcIp(httpRequest.getRemoteAddr())
                    .status(200)
                    .duration(duration)
                    .build())
                .build(),
            httpRequest.getHeader("Authorization"),
            httpRequest.getHeader("X-Request-Id")
        );

        return response;
    }
}
```

### Spring Boot (PSAMA, HPDS, dictionary)

```java
@Configuration
public class LoggingConfig {

    @Bean
    public LoggingClient loggingClient(
            @Value("${logging.service.url:}") String url,
            @Value("${logging.service.api-key:}") String key) {
        if (url.isEmpty() || key.isEmpty()) {
            return LoggingClient.noOp();
        }
        return new LoggingClient(
            LoggingClientConfig.builder(url, key).clientType("auth").build()
        );
    }
}
```

`application.properties`:

```properties
logging.service.url=http://pic-sure-logging:80
logging.service.api-key=${LOGGING_API_KEY:}
```

Then autowire it:

```java
@Service
public class AuthService {

    @Autowired
    LoggingClient loggingClient;

    public Token login(Credentials creds, HttpServletRequest httpRequest) {
        boolean success = authenticate(creds);

        loggingClient.send(LoggingEvent.builder("LOGIN")
            .action(success ? "success" : "failure")
            .request(RequestInfo.builder()
                .method("POST")
                .url("/login")
                .srcIp(httpRequest.getRemoteAddr())
                .build())
            .metadata(Map.of("email", creds.getEmail()))
            .build());

        return token;
    }
}
```

## Configuration Options

```java
LoggingClientConfig.builder(baseUrl, apiKey)
    .clientType("api")                        // default client_type for all events
    .connectTimeout(Duration.ofSeconds(5))    // TCP connect timeout (default: 5s)
    .requestTimeout(Duration.ofSeconds(10))   // HTTP request timeout (default: 10s)
    .build();
```

## Validation

The client enforces the same limits as the server:

- `event_type` is required (enforced at builder time)
- `metadata` map must not exceed 50 keys
- `error` map must not exceed 20 keys

## Resilience

- **Async fire-and-forget** — `send()` returns void immediately; the HTTP call runs on the JDK's internal executor
- **Never throws** — serialization errors are caught, connection failures are handled via `CompletableFuture.exceptionally()`, all failures logged at WARN
- **No retries, no circuit breaker** — lost events during outages are acceptable; failing user requests is not
- **No-op fallback** — when env vars are missing, producers return `LoggingClient.noOp()` so existing deployments without the logging service aren't affected

## Building

```bash
mvn clean install              # build + test
mvn clean install -DskipTests  # build only
mvn spotless:apply             # auto-format code
mvn spotless:check             # check formatting (CI uses this)
```

Requires Java 11+.
