package edu.harvard.hms.dbmi.avillach.query.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sample dataset configuration. The bean is enabled by {@link SampleConfig}; this class remains a plain bindable POJO so tests can
 * construct it directly.
 */
@ConfigurationProperties(prefix = "sample")
public class SampleProperties {

    /**
     * Whether a query may ask for a sample dataset. False (the default) refuses every query that sets {@code sample}, instead of ignoring
     * the field and returning the full export.
     */
    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
