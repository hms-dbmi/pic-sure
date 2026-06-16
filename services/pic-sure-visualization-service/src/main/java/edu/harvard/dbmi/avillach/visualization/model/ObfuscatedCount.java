package edu.harvard.dbmi.avillach.visualization.model;

/**
 * One chart value as exchanged with aggregate-data-sharing and the frontend.
 *
 * @param count the numeric value the bar renders at
 * @param display the human-readable label (e.g. "45000", "222 ±3", "< 10")
 * @param variance half-width of the uncertainty band around count, or null when the value is exact
 *        (authorized path). Consumers render the band as [max(0, count - variance), count + variance];
 *        below-threshold values are encoded as count 0 with variance threshold-1.
 */
public record ObfuscatedCount(int count, String display, Integer variance) {

    /**
     * Convenience constructor for exact values carrying no uncertainty band.
     */
    public ObfuscatedCount(int count, String display) {
        this(count, display, null);
    }

    /**
     * Wraps a plain (non-obfuscated) integer count. The display is just the
     * stringified number; this is the right factory for the authorized path
     * where no threshold floor or variance applies.
     */
    public static ObfuscatedCount ofInt(int count) {
        return new ObfuscatedCount(count, Integer.toString(count), null);
    }
}
