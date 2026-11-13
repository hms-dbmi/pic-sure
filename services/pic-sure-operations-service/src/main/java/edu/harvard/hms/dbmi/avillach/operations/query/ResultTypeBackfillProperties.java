package edu.harvard.hms.dbmi.avillach.operations.query;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of {@link ResultTypeBackfill}, bound from {@code picsure.operations.result-type-backfill.*}. A setting that is absent or blank
 * takes its default. A value out of range fails the bind, which stops the service from starting and names the setting.
 *
 * @param enabled whether the pass runs after startup; true when not set
 * @param batchSize the largest number of rows read in one page, at least 1; 200 when not set
 * @param pause how long the pass waits between two pages, zero or more; 250 milliseconds when not set
 */
@ConfigurationProperties(prefix = ResultTypeBackfillProperties.PREFIX)
public record ResultTypeBackfillProperties(Boolean enabled, Integer batchSize, Duration pause) {

    static final String PREFIX = "picsure.operations.result-type-backfill";
    static final int DEFAULT_BATCH_SIZE = 200;
    static final Duration DEFAULT_PAUSE = Duration.ofMillis(250);

    /**
     * Fills in the defaults and checks the ranges.
     *
     * @throws IllegalArgumentException when the batch size is below 1 or the pause is negative
     */
    public ResultTypeBackfillProperties {
        if (enabled == null) {
            enabled = Boolean.TRUE;
        }
        if (batchSize == null) {
            batchSize = DEFAULT_BATCH_SIZE;
        }
        if (pause == null) {
            pause = DEFAULT_PAUSE;
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException(PREFIX + ".batch-size must be at least 1, but is " + batchSize);
        }
        if (pause.isNegative()) {
            throw new IllegalArgumentException(PREFIX + ".pause must not be negative, but is " + pause);
        }
    }
}
