package edu.harvard.dbmi.avillach.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

final class JdkHttpSender implements Sender {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingClient.class);

    private final java.net.http.HttpClient httpClient;

    JdkHttpSender(LoggingClientConfig config) {
        this.httpClient = java.net.http.HttpClient.newBuilder().connectTimeout(config.getConnectTimeout()).build();
    }

    @Override
    public void send(
        byte[] body, URI auditEndpoint, LoggingClientConfig config, LoggingEvent resolved, String bearerToken, String requestId
    ) {

        java.net.http.HttpRequest.Builder requestBuilder = java.net.http.HttpRequest.newBuilder().uri(auditEndpoint)
            .timeout(config.getRequestTimeout()).header("Content-Type", "application/json").header("X-API-Key", config.getApiKey())
            .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body));

        if (bearerToken != null && !bearerToken.isEmpty()) {
            requestBuilder.header("Authorization", bearerToken);
        }
        if (requestId != null && !requestId.isEmpty()) {
            requestBuilder.header("X-Request-Id", requestId);
        }

        httpClient.sendAsync(requestBuilder.build(), java.net.http.HttpResponse.BodyHandlers.discarding()).thenAccept(response -> {
            if (response.statusCode() >= 300) {
                LOG.warn("logging-client: server returned {} for event_type={}", response.statusCode(), resolved.getEventType());
            }
        }).exceptionally(throwable -> {
            LOG.warn(
                "logging-client: failed to send event_type={}: {} - {}", resolved.getEventType(), throwable.getClass().getSimpleName(),
                LoggingClient.sanitizeExceptionMessageForSender(throwable)
            );
            return null;
        });
    }
}
