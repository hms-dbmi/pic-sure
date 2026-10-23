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
    /**
     * How often {@code StudyConsentPathProvider} evicts its cached study-consents path list, so a dataset reload is picked up within this
     * many seconds. The list is dataset-static, so caching removes one HPDS {@code /search} per open count. Disable the cache entirely with
     * {@code spring.cache.type=none}.
     */
    private int studyConsentsCacheTtlSec = 300;

    private final Obfuscation obfuscation = new Obfuscation();

    public static class Obfuscation {
        /**
         * Consent threshold for consent groups, studies and the total; values below 10 are raised to 10. Consent buckets are this wide
         * and the total rounds up to half of it.
         */
        private int consentThreshold = 10;
        /** Chart buckets below this show "< chartThreshold"; others round up to half of it, with that as their band. */
        private int chartThreshold = 10;
        /** No chart is returned for a cohort smaller than this. */
        private int chartMinimumCohort = 50;
        /**
         * When true, a studies-consents CROSS_COUNT returns only the obfuscated total to the client; the per-study and per-consent-group
         * counts are still computed (so study sums and mismatch warnings run) but dropped from the response. Defends open access against
         * reconstructing a consent group from its study and sibling counts.
         */
        private boolean crossCountTotalOnly = true;

        public int getConsentThreshold() {
            return consentThreshold;
        }

        public void setConsentThreshold(int consentThreshold) {
            this.consentThreshold = consentThreshold;
        }

        public int getChartThreshold() {
            return chartThreshold;
        }

        public void setChartThreshold(int chartThreshold) {
            this.chartThreshold = chartThreshold;
        }

        public int getChartMinimumCohort() {
            return chartMinimumCohort;
        }

        public void setChartMinimumCohort(int chartMinimumCohort) {
            this.chartMinimumCohort = chartMinimumCohort;
        }

        public boolean isCrossCountTotalOnly() {
            return crossCountTotalOnly;
        }

        public void setCrossCountTotalOnly(boolean crossCountTotalOnly) {
            this.crossCountTotalOnly = crossCountTotalOnly;
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

    public int getStudyConsentsCacheTtlSec() {
        return studyConsentsCacheTtlSec;
    }

    public void setStudyConsentsCacheTtlSec(int s) {
        this.studyConsentsCacheTtlSec = s;
    }

    public Obfuscation getObfuscation() {
        return obfuscation;
    }

    /** True when a visualization service is configured (gate for continuous binning). */
    public boolean hasVisualization() {
        return visualizationUrl != null && !visualizationUrl.isBlank();
    }
}
