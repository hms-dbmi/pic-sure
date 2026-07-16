package edu.harvard.hms.dbmi.avillach.gateway.health;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config-driven list of downstreams the gateway's deep-health composer probes, bound from {@code picsure.gateway.health.*}. Backs
 * {@link SystemHealthService}.
 */
@ConfigurationProperties(prefix = "picsure.gateway.health")
public record DownstreamHealthProperties(
    List<MonitoredDownstream> downstreams, Integer connectTimeoutMs, Integer readTimeoutMs, String applicationToken
) {
    public DownstreamHealthProperties {
        downstreams = downstreams == null ? List.of() : List.copyOf(downstreams);
        if (connectTimeoutMs == null) {
            connectTimeoutMs = 1000;
        }
        if (readTimeoutMs == null) {
            readTimeoutMs = 2000;
        }
    }
}
