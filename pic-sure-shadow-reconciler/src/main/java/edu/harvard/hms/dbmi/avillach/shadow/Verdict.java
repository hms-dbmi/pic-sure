package edu.harvard.hms.dbmi.avillach.shadow;

/** A classifier outcome: the {@link VerdictType}, plus an optional short machine-readable sub-reason. */
public record Verdict(VerdictType type, String reason) {

    public static Verdict of(VerdictType type) {
        return new Verdict(type, null);
    }

    public static Verdict of(VerdictType type, String reason) {
        return new Verdict(type, reason);
    }
}
