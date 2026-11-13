package edu.harvard.hms.dbmi.avillach.operations.dataset;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of {@link DatasetBringOver}, bound from {@code picsure.operations.dataset-bring-over.*}. A setting that is absent or blank takes
 * its default.
 *
 * <p>The step is off unless it is turned on. A dataset it has brought over points at a query row that only a stack with query owners can
 * read, so it is turned on once no older stack reads the same database any more.
 *
 * @param enabled whether a dataset read brings the caller's datasets over to query rows the caller owns; false when not set
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
