package edu.harvard.dbmi.avillach.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class JdkHttpSender implements Sender {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingClient.class);

    /**
     * Maximum sends awaiting completion before new events are dropped (drop-newest). Combined with the per-request timeout this bounds the
     * memory a slow or downed logging service can pin in the calling service; the caller is never blocked.
     */
    static final int DEFAULT_MAX_IN_FLIGHT = 1000;

    private static final long DROP_WARN_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

    private final java.net.http.HttpClient httpClient;
    private final int maxInFlight;
    private final Semaphore inFlight;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong lastDropWarnNanos;

    JdkHttpSender(LoggingClientConfig config) {
        this(config, DEFAULT_MAX_IN_FLIGHT);
    }

    JdkHttpSender(LoggingClientConfig config, int maxInFlight) {
        this.httpClient = java.net.http.HttpClient.newBuilder().connectTimeout(config.getConnectTimeout()).build();
        this.maxInFlight = maxInFlight;
        this.inFlight = new Semaphore(maxInFlight);
        // Seed one interval in the past so the very first drop is warned about immediately.
        this.lastDropWarnNanos = new AtomicLong(System.nanoTime() - DROP_WARN_INTERVAL_NANOS);
    }

    @Override
    public void send(
        byte[] body, URI auditEndpoint, LoggingClientConfig config, LoggingEvent resolved, String bearerToken, String requestId
    ) {

        if (!inFlight.tryAcquire()) {
            recordDrop(resolved);
            return;
        }

        java.net.http.HttpRequest.Builder requestBuilder = java.net.http.HttpRequest.newBuilder().uri(auditEndpoint)
            .timeout(config.getRequestTimeout()).header("Content-Type", "application/json").header("X-API-Key", config.getApiKey())
            .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body));

        if (bearerToken != null && !bearerToken.isEmpty()) {
            requestBuilder.header("Authorization", bearerToken);
        }
        if (requestId != null && !requestId.isEmpty()) {
            requestBuilder.header("X-Request-Id", requestId);
        }

        try {
            httpClient.sendAsync(requestBuilder.build(), java.net.http.HttpResponse.BodyHandlers.discarding())
                .whenComplete((response, throwable) -> {
                    inFlight.release();
                    if (throwable != null) {
                        LOG.warn(
                            "logging-client: failed to send event_type={}: {} - {}", resolved.getEventType(),
                            throwable.getClass().getSimpleName(), LoggingClient.sanitizeExceptionMessageForSender(throwable)
                        );
                    } else if (response.statusCode() >= 300) {
                        LOG.warn("logging-client: server returned {} for event_type={}", response.statusCode(), resolved.getEventType());
                    }
                });
        } catch (RuntimeException e) {
            inFlight.release();
            LOG.warn(
                "logging-client: failed to submit event_type={}: {} - {}", resolved.getEventType(), e.getClass().getSimpleName(),
                LoggingClient.sanitizeExceptionMessageForSender(e)
            );
        }
    }

    /** Drop-newest under backpressure; warn at most once per interval, carrying a running total so drops stay visible without log spam. */
    private void recordDrop(LoggingEvent resolved) {
        long droppedSoFar = dropped.incrementAndGet();
        long now = System.nanoTime();
        long last = lastDropWarnNanos.get();
        if (now - last >= DROP_WARN_INTERVAL_NANOS && lastDropWarnNanos.compareAndSet(last, now)) {
            LOG.warn(
                "logging-client: dropping event_type={} - {} sends already in flight ({} events dropped so far)", resolved.getEventType(),
                maxInFlight, droppedSoFar
            );
        }
    }

    long droppedCount() {
        return dropped.get();
    }

    int availablePermits() {
        return inFlight.availablePermits();
    }
}
