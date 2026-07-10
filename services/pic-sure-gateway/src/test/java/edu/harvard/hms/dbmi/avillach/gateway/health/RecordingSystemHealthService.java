package edu.harvard.hms.dbmi.avillach.gateway.health;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Test-only {@link SystemHealthService} subclass that overrides the protected {@code probe} override point so unit tests can exercise dedup
 * / rollup semantics without any real HTTP traffic. The real HTTP path is covered by {@link SystemHealthServiceIT} via WireMock.
 */
class RecordingSystemHealthService extends SystemHealthService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> probedUrls = new CopyOnWriteArrayList<>();
    private final Set<String> downUrls = ConcurrentHashMap.newKeySet();
    private final Map<String, Stub> stubs = new ConcurrentHashMap<>();

    RecordingSystemHealthService(DownstreamHealthProperties props) {
        super(props);
    }

    List<String> probedUrls() {
        return Collections.unmodifiableList(probedUrls);
    }

    void markDown(String resolvedUrl) {
        downUrls.add(resolvedUrl);
    }

    void stub(String resolvedUrl, int status, String body) {
        stubs.put(resolvedUrl, new Stub(status, body));
    }

    @Override
    protected boolean probe(MonitoredDownstream d) {
        String url = d.resolvedUrl();
        probedUrls.add(url);

        if (downUrls.contains(url)) {
            return false;
        }

        Stub stub = stubs.get(url);
        if (stub == null) {
            return true;
        }
        if (stub.status() != d.successStatus()) {
            return false;
        }
        if (d.requireStatusUp()) {
            try {
                JsonNode node = MAPPER.readTree(stub.body());
                return node.hasNonNull("status") && "UP".equalsIgnoreCase(node.get("status").asText());
            } catch (Exception ex) {
                return false;
            }
        }
        return true;
    }

    private record Stub(int status, String body) {
    }
}
