package edu.harvard.dbmi.avillach.logging;

import java.time.Duration;

/**
 * Configuration for {@link LoggingClient}. Use {@link #builder(String, String)} to construct instances.
 */
public final class LoggingClientConfig {

    private final String baseUrl;
    private final String apiKey;
    private final String clientType;
    private final Duration connectTimeout;
    private final Duration requestTimeout;

    private LoggingClientConfig(Builder builder) {
        this.baseUrl = normalizeUrl(builder.baseUrl);
        this.apiKey = builder.apiKey;
        this.clientType = builder.clientType;
        this.connectTimeout = builder.connectTimeout;
        this.requestTimeout = builder.requestTimeout;
    }

    private static String normalizeUrl(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getClientType() {
        return clientType;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    /**
     * Create a builder with required parameters.
     *
     * @param baseUrl the base URL of the PIC-SURE Logging service (e.g. "http://pic-sure-logging:80")
     * @param apiKey the API key for authentication
     * @return a new builder
     */
    public static Builder builder(String baseUrl, String apiKey) {
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("apiKey is required");
        }
        return new Builder(baseUrl, apiKey);
    }

    public static final class Builder {
        private final String baseUrl;
        private final String apiKey;
        private String clientType;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(10);

        private Builder(String baseUrl, String apiKey) {
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
        }

        public Builder clientType(String clientType) {
            this.clientType = clientType;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public LoggingClientConfig build() {
            return new LoggingClientConfig(this);
        }
    }
}
