package edu.harvard.hms.dbmi.avillach.query.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-backend HPDS connection settings. There is a single pooled {@link org.springframework.web.client.RestClient} (see
 * {@link HpdsClientConfig}) with no base URL of its own -- callers select the target backend's absolute base + service token via
 * {@link edu.harvard.hms.dbmi.avillach.query.hpds.HpdsBackendSelector} and pass it per-call.
 */
@ConfigurationProperties(prefix = "hpds")
public class HpdsProperties {

    /** HPDS_AUTH_URL -- the auth (non-obfuscated) backend base, e.g. http://hpds:8080/PIC-SURE */
    private String authUrl;
    /** HPDS_AUTH_TOKEN -- the service Bearer token for the auth backend. SECRET. */
    private String authToken;
    /** HPDS_OPEN_URL -- the open (aggregate/obfuscated) backend base. In AIO both equal authUrl. */
    private String openUrl;
    /** HPDS_OPEN_TOKEN -- the service Bearer token for the open backend. SECRET. (May be blank if not needed.) */
    private String openToken;
    /**
     * HPDS_API_PATH, the path HPDS serves its query API under, appended to each backend base by every HPDS client in this service, the
     * aggregate client included. Defaults to {@code /v3} when the variable is unset. Set it to the empty string once HPDS serves that API
     * at the base itself.
     */
    private String apiPath = "/v3";
    /** Health probe path appended to each backend base. */
    private String healthPath = "/actuator/health";
    private int connectTimeoutSec = 10;
    private int readTimeoutSec = 300;
    /** The Rust HPDS settings, bound from {@code hpds.rs.*}. */
    private final Rs rs = new Rs();

    /** @return the Rust HPDS routing settings */
    public Rs getRs() {
        return rs;
    }

    public String getAuthUrl() {
        return authUrl;
    }

    public void setAuthUrl(String authUrl) {
        this.authUrl = authUrl;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public String getOpenUrl() {
        return openUrl;
    }

    public void setOpenUrl(String openUrl) {
        this.openUrl = openUrl;
    }

    public String getOpenToken() {
        return openToken;
    }

    public void setOpenToken(String openToken) {
        this.openToken = openToken;
    }

    /** @return the configured API path, or the empty string when it is bound to {@code null} */
    public String getApiPath() {
        return apiPath == null ? "" : apiPath;
    }

    public void setApiPath(String apiPath) {
        this.apiPath = apiPath;
    }

    public String getHealthPath() {
        return healthPath;
    }

    public void setHealthPath(String healthPath) {
        this.healthPath = healthPath;
    }

    public int getConnectTimeoutSec() {
        return connectTimeoutSec;
    }

    public void setConnectTimeoutSec(int connectTimeoutSec) {
        this.connectTimeoutSec = connectTimeoutSec;
    }

    public int getReadTimeoutSec() {
        return readTimeoutSec;
    }

    public void setReadTimeoutSec(int readTimeoutSec) {
        this.readTimeoutSec = readTimeoutSec;
    }

    /** How the query service uses the Rust HPDS backend: not at all, as a mirrored shadow of Java, or as the answering backend. */
    public enum RsMode {
        /** Every call goes to Java HPDS only. */
        OFF,
        /** Java answers; a sample of eligible calls is mirrored to Rust after Java's answer and the two answers are compared. */
        SHADOW,
        /** Rust answers eligible calls; any Rust failure falls back to Java's answer for that call. */
        SERVE
    }

    /**
     * Settings for the Rust HPDS backend that sits beside the Java auth backend. Every value is read once at startup, so changing one
     * (including the kill switch {@code HPDS_RS_MODE=off}) takes a query service restart.
     */
    public static class Rs {

        /** The result types routed or mirrored when {@code HPDS_RS_TYPES} is unset. */
        public static final List<String> DEFAULT_TYPES =
            List.of("COUNT", "CROSS_COUNT", "CATEGORICAL_CROSS_COUNT", "CONTINUOUS_CROSS_COUNT");

        private String url;
        private String token;
        private RsMode mode = RsMode.OFF;
        private Set<String> types = new LinkedHashSet<>(DEFAULT_TYPES);
        private double shadowSample = 0.1;
        private int shadowMaxInFlight = 4;
        private int timeoutMs = 30000;

        /** @return the Rust HPDS base URL ({@code HPDS_RS_URL}), to which the API path is appended; unset means routing is off */
        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        /** @return the service Bearer token sent to Rust HPDS on query calls ({@code HPDS_RS_TOKEN}) */
        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        /** @return the routing mode ({@code HPDS_RS_MODE}); a blank value binds to {@code null} and is read as {@link RsMode#OFF} */
        public RsMode getMode() {
            return mode;
        }

        public void setMode(RsMode mode) {
            this.mode = mode;
        }

        /** @return the result type names routed or mirrored ({@code HPDS_RS_TYPES}), with {@code SEARCH} and {@code SEARCH_VALUES} */
        public Set<String> getTypes() {
            return types;
        }

        public void setTypes(Set<String> types) {
            this.types = types;
        }

        /** @return the share of eligible calls mirrored in shadow mode ({@code HPDS_RS_SHADOW_SAMPLE}), from 0 to 1 */
        public double getShadowSample() {
            return shadowSample;
        }

        public void setShadowSample(double shadowSample) {
            this.shadowSample = shadowSample;
        }

        /** @return the most mirror calls in flight at once ({@code HPDS_RS_SHADOW_MAX_IN_FLIGHT}); a call over it is dropped */
        public int getShadowMaxInFlight() {
            return shadowMaxInFlight;
        }

        public void setShadowMaxInFlight(int shadowMaxInFlight) {
            this.shadowMaxInFlight = shadowMaxInFlight;
        }

        /** @return the read timeout of Rust calls in milliseconds ({@code HPDS_RS_TIMEOUT_MS}) */
        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }
}
