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
     * the field and returning the full export. The field is a {@link Boolean} so that a blank configured value, which is what an env-file
     * line {@code SAMPLE_ENABLED=} produces, binds as no value and leaves the default in place. A value that is neither blank nor a boolean
     * still fails the bind.
     */
    private Boolean enabled = Boolean.FALSE;

    /** Returns whether sampling is turned on. An unset or blank setting is off. */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
