package edu.harvard.hms.dbmi.avillach.gateway.health;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * Exercises the real HTTP path (unlike {@link SystemHealthServiceTest}, which stubs {@code probe} directly): a Spring Boot Actuator-style
 * sibling ({@code {"status":"UP"}}), a logging {@code /health} endpoint (200 with a differently-shaped body, no status predicate), and a
 * downstream that is down.
 */
class SystemHealthServiceIT {

    static WireMockServer actuatorUp; // Spring Boot sibling: {"status":"UP"}
    static WireMockServer loggingUp; // Logging /health: 200 {"status":"healthy"}
    static WireMockServer down;

    @BeforeAll
    static void start() {
        // http2PlainDisabled avoids a known JDK HttpClient <-> WireMock(Jetty) h2c upgrade bug that manifests as
        // "RST_STREAM: Stream cancelled" when RestClient's default JDK-backed request factory is used.
        actuatorUp = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        loggingUp = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        down = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        actuatorUp.start();
        loggingUp.start();
        down.start();
        actuatorUp.stubFor(get(urlEqualTo("/actuator/health")).willReturn(okJson("{\"status\":\"UP\"}")));
        loggingUp.stubFor(get(urlEqualTo("/health")).willReturn(okJson("{\"status\":\"healthy\"}")));
        down.stubFor(get(urlEqualTo("/actuator/health")).willReturn(aResponse().withStatus(503)));
    }

    @AfterAll
    static void stop() {
        actuatorUp.stop();
        loggingUp.stop();
        down.stop();
    }

    @Test
    void aggregatesDeepActuatorAndLoggingHealthAndOneDownDegradesIt() {
        DownstreamHealthProperties props = new DownstreamHealthProperties(
            List.of(
                new MonitoredDownstream("psama", "http://127.0.0.1:" + actuatorUp.port(), null, null, null, false, 200, true),
                new MonitoredDownstream("logging", "http://127.0.0.1:" + loggingUp.port(), "/health", null, null, false, 200, false),
                new MonitoredDownstream("hpds", "http://127.0.0.1:" + down.port(), null, null, null, false, 200, true)
            ), 500, 1000, null
        );

        AggregateHealth agg = new SystemHealthService(props).check();

        assertThat(agg.running()).isFalse(); // hpds down -> DEGRADED
        assertThat(agg.components()).filteredOn(c -> c.name().equals("psama")).allMatch(DownstreamHealth::up);
        assertThat(agg.components()).filteredOn(c -> c.name().equals("logging")).allMatch(DownstreamHealth::up);
        assertThat(agg.components()).filteredOn(c -> c.name().equals("hpds")).noneMatch(DownstreamHealth::up);
    }
}
