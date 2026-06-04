package edu.harvard.hms.dbmi.avillach;

import java.util.Objects;

public final class ObfuscatedCount {

    private final int count;
    private final String display;

    public ObfuscatedCount(int count, String display) {
        this.count = count;
        this.display = display;
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
