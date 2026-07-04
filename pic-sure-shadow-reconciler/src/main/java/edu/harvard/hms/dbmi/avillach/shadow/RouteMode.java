package edu.harvard.hms.dbmi.avillach.shadow;

/**
 * Whether a route's raw-path variant affects PSAMA's consent-rule evaluation (DECISION_AFFECTING, e.g. the {@code /v3} prefix) or is a
 * purely cosmetic raw-to-canonical rewrite (COSMETIC).
 */
public enum RouteMode {
    COSMETIC, DECISION_AFFECTING
}
