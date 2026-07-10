package edu.harvard.hms.dbmi.avillach.query.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for {@link edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient}, the DB-free persistence path to
 * pic-sure-operations-service's internal query API.
 */
@ConfigurationProperties(prefix = "picsure.query.operations")
public class OperationsProperties {

    /** OPERATIONS_SERVICE_URL -- base URL of pic-sure-operations-service, e.g. http://operations-service:8080 */
    private String baseUrl;
    /**
     * QUERY_SERVICE_INTERNAL_TOKEN -- shared secret sent as {@code X-PIC-SURE-INTERNAL-TOKEN} on every call, checked by
     * operations-service's {@code InternalTokenFilter} against its own {@code picsure.operations.internal-token}. SECRET.
     */
    private String internalToken;
    private int connectTimeoutSec = 10;
    private int readTimeoutSec = 60;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
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
