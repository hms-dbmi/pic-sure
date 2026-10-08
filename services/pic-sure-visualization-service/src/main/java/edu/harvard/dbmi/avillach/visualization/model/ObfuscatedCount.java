package edu.harvard.dbmi.avillach.visualization.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One chart value as exchanged with aggregate-data-sharing and the frontend.
 *
 * @param count the numeric value the bar renders at
 * @param display the human-readable label: the exact count, the obfuscated count with its variance, or the below-threshold marker
 * @param variance half-width of the uncertainty band around count, or null when the value is exact (authorized path). Consumers render the
 *        band as [max(0, count - variance), count + variance]; below-threshold values are encoded as count 0 with variance threshold-1.
 */
@Schema(
    description = "One chart value: the number a bar is drawn at, the text shown for it, and the half-width of its uncertainty band. "
        + "The authorized backend sends exact counts with a null `variance`. The open backend sends obfuscated counts."
)
public record ObfuscatedCount(
    @Schema(
        description = "The value the bar is drawn at. An exact participant count on the authorized backend. On the open backend, the "
            + "obfuscated count, or 0 when the true count is below the obfuscation threshold.",
        example = "1234", requiredMode = Schema.RequiredMode.REQUIRED
    ) int count,
    @Schema(
        description = "The label shown for the value. On the authorized backend, the exact count as text. On the open backend, the "
            + "result is obfuscated: the label is the obfuscated count with its variance, or a marker that the true count is below the "
            + "obfuscation threshold.",
        example = "1234 ±3", requiredMode = Schema.RequiredMode.REQUIRED
    ) String display,
    @Schema(
        description = "Half-width of the uncertainty band around `count`. The band runs from `max(0, count - variance)` to "
            + "`count + variance`. Null when the count is exact. A below-threshold value has a `count` of 0 and a variance of the "
            + "threshold minus one.",
        example = "3"
    ) Integer variance
) {

    /**
     * Convenience constructor for exact values carrying no uncertainty band.
     */
    public ObfuscatedCount(int count, String display) {
        this(count, display, null);
    }

    /**
     * Wraps a plain (non-obfuscated) integer count. The display is just the stringified number; this is the right factory for the
     * authorized path where no threshold floor or variance applies.
     */
    public static ObfuscatedCount ofInt(int count) {
        return new ObfuscatedCount(count, Integer.toString(count), null);
    }
}
