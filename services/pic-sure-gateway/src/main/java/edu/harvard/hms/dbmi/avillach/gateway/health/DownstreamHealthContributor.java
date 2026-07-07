package edu.harvard.hms.dbmi.avillach.gateway.health;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.NamedContributor;
import org.springframework.stereotype.Component;

/**
 * Surfaces each monitored downstream as a component under {@code /actuator/health}. One aggregate probe per scrape (the
 * {@code /system/status} throttle does not apply here; Actuator's scrape interval governs frequency). Unaffected by the low-priority
 * catch-all gateway route: Actuator's own {@code WebMvcEndpointHandlerMapping} is order -100, ahead of the gateway's
 * {@code RouterFunctionMapping} (order -1).
 */
@Component("downstreams")
public class DownstreamHealthContributor implements CompositeHealthContributor {

    private final SystemHealthService health;

    public DownstreamHealthContributor(SystemHealthService health) {
        this.health = health;
    }

    private Map<String, HealthContributor> snapshot() {
        Map<String, HealthContributor> map = new LinkedHashMap<>();
        for (DownstreamHealth c : health.check().components()) {
            HealthIndicator indicator =
                () -> (c.up() ? Health.up() : Health.down()).withDetail("url", c.resolvedUrl()).withDetail("detail", c.detail()).build();
            map.put(c.name(), indicator);
        }
        return map;
    }

    @Override
    public HealthContributor getContributor(String name) {
        return snapshot().get(name);
    }

    @Override
    public Iterator<NamedContributor<HealthContributor>> iterator() {
        Map<String, HealthContributor> map = snapshot();
        return map.entrySet().stream().map(e -> NamedContributor.of(e.getKey(), e.getValue())).iterator();
    }
}
