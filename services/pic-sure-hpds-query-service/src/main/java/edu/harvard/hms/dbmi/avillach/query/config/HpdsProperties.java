package edu.harvard.hms.dbmi.avillach.query.config;

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
    /** Health probe path appended to each backend base. */
    private String healthPath = "/actuator/health";
    private int connectTimeoutSec = 10;
    private int readTimeoutSec = 300;

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
}
