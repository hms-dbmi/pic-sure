package edu.harvard.hms.dbmi.avillach.query.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Aggregate/obfuscation config (replaces the WAR's {@code resource.properties} + DB resource lookup). The {@code @ConfigurationProperties}
 * bean is declared/enabled in the aggregate wiring config (a later task); this class is a plain bindable POJO so it can also be constructed
 * directly in tests.
 */
@ConfigurationProperties(prefix = "aggregate")
public class AggregateProperties {

    /** Open HPDS backend; same value as HPDS_OPEN_URL (the query service's open backend). */
    private String hpdsOpenUrl;
    /** Bearer token for the open HPDS backend (+ visualization); same value as HPDS_OPEN_TOKEN. Was target.picsure.token. */
    private String hpdsOpenToken;
    /**
     * Visualization service base URL; replaces the DB ResourceRepository.getById(visualizationResourceId). Blank = no binning (raw
     * fallback).
     */
    private String visualizationUrl;
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
