package edu.harvard.hms.dbmi.avillach.gateway.health;

import java.util.List;

/** Rollup over all probed downstreams. */
public record AggregateHealth(boolean running, List<DownstreamHealth> components) {
    public static AggregateHealth from(List<DownstreamHealth> components) {
        boolean running = components.stream().allMatch(DownstreamHealth::up);
        return new AggregateHealth(running, components);
    }
}
