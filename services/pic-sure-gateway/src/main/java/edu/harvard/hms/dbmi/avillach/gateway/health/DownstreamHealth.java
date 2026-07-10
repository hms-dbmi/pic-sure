package edu.harvard.hms.dbmi.avillach.gateway.health;

/** Result of probing a single downstream. */
public record DownstreamHealth(String name, String resolvedUrl, boolean up, String detail) {
}
