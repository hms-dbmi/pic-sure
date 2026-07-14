package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import edu.harvard.hms.dbmi.avillach.gateway.health.DownstreamHealthProperties;
import edu.harvard.hms.dbmi.avillach.gateway.health.MonitoredDownstream;

/**
 * Under the {@code aio} profile, the deep-health downstream list must aggregate the two new services -- operations-service (the sole DB
 * owner) and query-service (DB-free {@code /hpds/**}) -- alongside the existing entries, mirroring their shape (Actuator health,
 * {@code require-status-up: true}).
 */
@SpringBootTest
@ActiveProfiles("aio")
class DownstreamHealthPropertiesAioBindingTest {

    @Autowired
    private DownstreamHealthProperties props;

    private Optional<MonitoredDownstream> byName(String name) {
        return props.downstreams().stream().filter(d -> d.name().equals(name)).findFirst();
    }

    @Test
    void operationsServiceIsAggregatedWithActuatorHealthAndRequiresStatusUp() {
        MonitoredDownstream d = byName("operations-service").orElseThrow();
        assertThat(d.baseUrl()).isEqualTo("http://pic-sure-operations-service:8080");
        assertThat(d.healthPath()).isEqualTo("/operations/actuator/health");
        assertThat(d.requireStatusUp()).isTrue();
    }

    @Test
    void queryServiceIsAggregatedWithActuatorHealthAndRequiresStatusUp() {
        MonitoredDownstream d = byName("query-service").orElseThrow();
        assertThat(d.baseUrl()).isEqualTo("http://pic-sure-hpds-query-service:8080");
        assertThat(d.healthPath()).isEqualTo("/actuator/health");
        assertThat(d.requireStatusUp()).isTrue();
    }
}
