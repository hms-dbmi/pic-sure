package edu.harvard.hms.dbmi.avillach.shadow;

/** The classifier's possible outcomes for a joined {@link Pair}, copied verbatim from the plan's Global Constraints. */
public enum VerdictType {
    MATCH, EXPECTED_DIFF, INTENTIONAL_BEHAVIOR_CHANGE, DIVERGENCE, UNPAIRED, SKIP
}
