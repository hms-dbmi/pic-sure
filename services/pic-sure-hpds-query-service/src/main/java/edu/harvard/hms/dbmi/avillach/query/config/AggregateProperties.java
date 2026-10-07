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
    private int connectTimeoutSec = 10;
    private int readTimeoutSec = 60;

    private final Obfuscation obfuscation = new Obfuscation();

    public static class Obfuscation {
        /**
         * Base consent threshold. Every other obfuscation value except {@link #chartMinimumCohort} is derived from it in
         * {@code ObfuscationService}.
         */
        private int consentThreshold = 5;
        /** No chart is returned for a cohort smaller than this. */
        private int chartMinimumCohort = 50;

        public int getConsentThreshold() {
            return consentThreshold;
        }

        public void setConsentThreshold(int consentThreshold) {
            this.consentThreshold = consentThreshold;
        }

        public int getChartMinimumCohort() {
            return chartMinimumCohort;
        }

        public void setChartMinimumCohort(int chartMinimumCohort) {
            this.chartMinimumCohort = chartMinimumCohort;
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
