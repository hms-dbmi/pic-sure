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
        /** COUNT and CROSS_COUNT. Zero disables obfuscation entirely -- supported, for deployments whose data is open by design. */
        private int threshold = 10; // ApplicationProperties.DEFAULT_OBFUSCATION_THRESHOLD
        /**
         * Multiple of {@link #threshold} used for chart buckets when no explicit chart threshold is configured. Charts expose the shape of
         * a distribution rather than a single number, so they default to a stricter cutoff than the count path.
         */
        private static final int DEFAULT_CHART_THRESHOLD_MULTIPLE = 2;

        /**
         * CATEGORICAL_CROSS_COUNT chart buckets. Null means "derive from {@link #threshold}", so raising the count threshold tightens
         * charts with it instead of silently leaving them looser.
         */
        private Integer categoricalThreshold;
        /** CONTINUOUS_CROSS_COUNT chart buckets; same derivation as {@link #categoricalThreshold}. */
        private Integer continuousThreshold;
        /**
         * Ceiling, in raw counts, on the variance a CROSS_COUNT aggregate may accumulate from suppressed descendants. Without it a concept
         * with many small children -- notably the injected study-consents list, whose parent is every consent path's prefix -- accumulates
         * a band wide enough to make its own total meaningless. Should be at least as large as {@code threshold}.
         */
        private int maxVariance = 50;

        public int getThreshold() {
            return threshold;
        }

        public void setThreshold(int t) {
            this.threshold = t;
        }

        public int getCategoricalThreshold() {
            return categoricalThreshold != null ? categoricalThreshold : defaultChartThreshold();
        }

        public void setCategoricalThreshold(Integer t) {
            this.categoricalThreshold = t;
        }

        public int getContinuousThreshold() {
            return continuousThreshold != null ? continuousThreshold : defaultChartThreshold();
        }

        public void setContinuousThreshold(Integer t) {
            this.continuousThreshold = t;
        }

        private int defaultChartThreshold() {
            return threshold * DEFAULT_CHART_THRESHOLD_MULTIPLE;
        }

        public int getMaxVariance() {
            return maxVariance;
        }

        public void setMaxVariance(int v) {
            this.maxVariance = v;
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
