package edu.harvard.hms.dbmi.avillach.operations.dataset;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of {@link DatasetBringOver}, bound from {@code picsure.operations.dataset-bring-over.*}. A setting that is absent or blank takes
 * its default.
 *
 * <p>The step is off unless it is turned on. It writes the general query of a dataset and, when needed, a {@code query_to_variant} row. It
 * writes no query row and no {@code queryId}, so a stack that does not know general queries reads the same datasets as before.
 *
 * @param enabled whether a dataset read files the caller's datasets under the general query of their stored query; false when not set
 */
@ConfigurationProperties(prefix = DatasetBringOverProperties.PREFIX)
public record DatasetBringOverProperties(Boolean enabled) {

    static final String PREFIX = "picsure.operations.dataset-bring-over";

    /** Fills in the default. */
    public DatasetBringOverProperties {
        if (enabled == null) {
            enabled = Boolean.FALSE;
        }
    }
}
