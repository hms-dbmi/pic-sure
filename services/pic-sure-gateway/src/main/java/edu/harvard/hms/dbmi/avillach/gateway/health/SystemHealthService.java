package edu.harvard.hms.dbmi.avillach.gateway.health;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pings each configured downstream in parallel, dedups by resolved URL, and rolls the results up into an aggregate RUNNING/DEGRADED verdict
 * (spec 3.8). One ping per service (no cascade): each service's own deep {@code /actuator/health} already covers its dependencies, so the
 * gateway does not double-probe a backend another service fronts. Downstreams that resolve to the same URL (e.g. hpds-open / hpds-auth
 * collapsing onto a single HPDS instance in an AIO deployment) are probed once and the result is fanned back out to every name sharing that
 * URL.
 */
public class SystemHealthService {

    private static final Logger log = LoggerFactory.getLogger(SystemHealthService.class);

    private final DownstreamHealthProperties props;
    private final RestClient http;
    private final ObjectMapper json = new ObjectMapper();

    public SystemHealthService(DownstreamHealthProperties props) {
        this.props = props;
        // Health probes run off the request path (a background composer), but a slow/hanging downstream must still
        // not tie up the aggregator indefinitely -- short, bounded connect/read timeouts, same pattern as
        // SecurityConfig's auth-boundary clients.
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
            .withConnectTimeout(Duration.ofMillis(props.connectTimeoutMs())).withReadTimeout(Duration.ofMillis(props.readTimeoutMs()));
        this.http = RestClient.builder().requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings)).build();
    }

    public AggregateHealth check() {
        // Group entries by resolved URL so a shared backend is probed once.
        Map<String, List<MonitoredDownstream>> byUrl = new LinkedHashMap<>();
        for (MonitoredDownstream d : props.downstreams()) {
            byUrl.computeIfAbsent(d.resolvedUrl(), k -> new ArrayList<>()).add(d);
        }

        List<CompletableFuture<List<DownstreamHealth>>> futures = new ArrayList<>();
        for (Map.Entry<String, List<MonitoredDownstream>> e : byUrl.entrySet()) {
            List<MonitoredDownstream> sharing = e.getValue();
            MonitoredDownstream representative = sharing.get(0);
            futures.add(CompletableFuture.supplyAsync(() -> {
                boolean up = probe(representative);
                String detail = up ? "up" : "unreachable";
                List<DownstreamHealth> results = new ArrayList<>();
                for (MonitoredDownstream d : sharing) {
                    results.add(new DownstreamHealth(d.name(), d.resolvedUrl(), up, detail));
                }
                return results;
            }));
        }

        List<DownstreamHealth> components = new ArrayList<>();
        for (CompletableFuture<List<DownstreamHealth>> f : futures) {
            components.addAll(f.join());
        }
        return AggregateHealth.from(components);
    }

    /** Probe a single downstream. Returns true iff it is considered up. Overridable for unit tests. */
    protected boolean probe(MonitoredDownstream d) {
        try {
            RestClient.RequestBodySpec spec = http.method(HttpMethod.valueOf(d.method())).uri(d.resolvedUrl());
            if (d.sendAppToken() && props.applicationToken() != null) {
                spec = spec.header("Authorization", "Bearer " + props.applicationToken());
            }
            if ("POST".equalsIgnoreCase(d.method())) {
                spec = spec.contentType(MediaType.APPLICATION_JSON).body(d.requestBody() == null ? "{}" : d.requestBody());
            }
            var response = spec.retrieve().toEntity(String.class);
            if (response.getStatusCode().value() != d.successStatus()) {
                return false;
            }
            if (d.requireStatusUp()) {
                String body = response.getBody();
                if (body == null) {
                    return false;
                }
                JsonNode node = json.readTree(body);
                return node.hasNonNull("status") && "UP".equalsIgnoreCase(node.get("status").asText());
            }
            return true;
        } catch (Exception ex) {
            log.warn("Health probe failed for {} ({}): {}", d.name(), d.resolvedUrl(), ex.getMessage());
            return false;
        }
    }
}
