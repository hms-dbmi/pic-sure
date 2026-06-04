package edu.harvard.hms.dbmi.avillach;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class ObfuscatedCount {

    @JsonProperty("count")
    private final int count;

    @JsonProperty("display")
    private final String display;

    @JsonCreator
    public ObfuscatedCount(
        @JsonProperty("count") int count,
        @JsonProperty("display") String display
    ) {
        this.count = count;
        this.display = display;
    }

    /**
     * Wraps a plain (non-obfuscated) integer count. The display is just the
     * stringified number; this is the right factory for the authorized path
     * where no threshold floor or variance applies.
     */
    public static ObfuscatedCount ofInt(int count) {
        return new ObfuscatedCount(count, Integer.toString(count));
    }

    public int count() {
        return count;
    }

    public String display() {
        return display;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ObfuscatedCount)) return false;
        ObfuscatedCount that = (ObfuscatedCount) other;
        return count == that.count && Objects.equals(display, that.display);
    }

    @Override
    public int hashCode() {
        return Objects.hash(count, display);
    }

    @Override
    public String toString() {
        return "ObfuscatedCount{count=" + count + ", display='" + display + "'}";
    }
}
