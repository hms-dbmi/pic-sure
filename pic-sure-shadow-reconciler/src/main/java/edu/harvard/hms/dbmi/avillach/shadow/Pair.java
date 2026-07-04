package edu.harvard.hms.dbmi.avillach.shadow;

/**
 * One correlation id's joined shadow records. Either side may be {@code null} when only one of the two systems (gateway / WildFly) emitted
 * a record for that correlation id.
 */
public record Pair(String correlationId, ShadowRecord gw, ShadowRecord wf) {
}
