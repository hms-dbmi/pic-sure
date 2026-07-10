package edu.harvard.hms.dbmi.avillach.gateway.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class SystemHealthServiceTest {

    private MonitoredDownstream up(String name, String url) {
        return new MonitoredDownstream(name, url, "/actuator/health", "GET", null, false, 200, false);
    }

    @Test
    void dedupsByResolvedUrl() {
        // In single-HPDS envs the open and auth HPDS collapse onto one URL.
        DownstreamHealthProperties props = new DownstreamHealthProperties(
            List.of(up("hpds-open", "http://hpds:8080"), up("hpds-auth", "http://hpds:8080")), 1000, 2000, null
        );
        RecordingSystemHealthService svc = new RecordingSystemHealthService(props);
        AggregateHealth agg = svc.check();
        assertThat(svc.probedUrls()).containsExactly("http://hpds:8080/actuator/health");
        assertThat(agg.components()).extracting(DownstreamHealth::name).containsExactlyInAnyOrder("hpds-open", "hpds-auth");
        assertThat(agg.running()).isTrue();
    }

    @Test
    void oneDownMakesAggregateDegraded() {
        DownstreamHealthProperties props =
            new DownstreamHealthProperties(List.of(up("a", "http://a:8080"), up("b", "http://b:8080")), 1000, 2000, null);
        RecordingSystemHealthService svc = new RecordingSystemHealthService(props);
        svc.markDown("http://b:8080/actuator/health");
        AggregateHealth agg = svc.check();
        assertThat(agg.running()).isFalse();
        assertThat(agg.components()).anySatisfy(c -> {
            assertThat(c.name()).isEqualTo("b");
            assertThat(c.up()).isFalse();
        });
    }

    @Test
    void successPredicateRequiresStatusUpBody() {
        MonitoredDownstream strict = new MonitoredDownstream("dict", "http://dict:8080", "/actuator/health", "GET", null, false, 200, true);
        RecordingSystemHealthService svc =
            new RecordingSystemHealthService(new DownstreamHealthProperties(List.of(strict), 1000, 2000, null));
        svc.stub("http://dict:8080/actuator/health", 200, "{\"status\":\"DOWN\"}");
        assertThat(svc.check().running()).isFalse();
        svc.stub("http://dict:8080/actuator/health", 200, "{\"status\":\"UP\"}");
        assertThat(svc.check().running()).isTrue();
    }
}
