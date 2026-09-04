package edu.harvard.hms.dbmi.avillach.query.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Aggregate and obfuscation configuration. The {@code @ConfigurationProperties} bean is enabled by the aggregate wiring config; this class
 * remains a plain bindable POJO so tests can construct it directly.
 */
@ConfigurationProperties(prefix = "aggregate")
public class AggregateProperties {

    /** Open HPDS backend; same value as HPDS_OPEN_URL (the query service's open backend). */
    private String hpdsOpenUrl;
    /** Bearer token for the open HPDS backend and visualization service; same value as HPDS_OPEN_TOKEN. */
    private String hpdsOpenToken;
    /**
     * Visualization service base URL. Blank means continuous obfuscation uses raw per-value counts without binning.
     */
    private String visualizationUrl;
    /** Optional resource UUID injected into the visualization {@code /bin/continuous} request body. */
    private String visualizationResourceId;
    /** Optional resource UUID injected into every downstream HPDS request body. */
    private String targetResourceId;
    private int connectTimeoutSec = 10;
    private int readTimeoutSec = 60;

    private final Obfuscation obfuscation = new Obfuscation();

    public static class Obfuscation {
        private int threshold = 10; // ApplicationProperties.DEFAULT_OBFUSCATION_THRESHOLD
        private int variance = 3; // ApplicationProperties.DEFAULT_OBFUSCATION_VARIANCE
        private String salt; // null/blank => random UUID at startup (ObfuscationService)

        public int getThreshold() {
            return threshold;
        }

        public void setThreshold(int t) {
            this.threshold = t;
        }

        public int getVariance() {
            return variance;
        }

        public void setVariance(int v) {
            this.variance = v;
        }

        public String getSalt() {
            return salt;
        }

        public void setSalt(String s) {
            this.salt = s;
        }
    }

    public String getHpdsOpenUrl() {
        return hpdsOpenUrl;
    }

    public void setHpdsOpenUrl(String u) {
        this.hpdsOpenUrl = u;
    }

    public String getHpdsOpenToken() {
        return hpdsOpenToken;
    }

    public void setHpdsOpenToken(String t) {
        this.hpdsOpenToken = t;
    }

    public String getVisualizationUrl() {
        return visualizationUrl;
    }

    public void setVisualizationUrl(String u) {
        this.visualizationUrl = u;
    }

    public String getVisualizationResourceId() {
        return visualizationResourceId;
    }

    public void setVisualizationResourceId(String id) {
        this.visualizationResourceId = id;
    }

    public String getTargetResourceId() {
        return targetResourceId;
    }

    public void setTargetResourceId(String id) {
        this.targetResourceId = id;
    }

    public int getConnectTimeoutSec() {
        return connectTimeoutSec;
    }

    public void setConnectTimeoutSec(int s) {
        this.connectTimeoutSec = s;
    }

    public int getReadTimeoutSec() {
        return readTimeoutSec;
    }

    public void setReadTimeoutSec(int s) {
        this.readTimeoutSec = s;
    }

    public Obfuscation getObfuscation() {
        return obfuscation;
    }

    /** True when a visualization service is configured (gate for continuous binning). */
    public boolean hasVisualization() {
        return visualizationUrl != null && !visualizationUrl.isBlank();
    }
}
