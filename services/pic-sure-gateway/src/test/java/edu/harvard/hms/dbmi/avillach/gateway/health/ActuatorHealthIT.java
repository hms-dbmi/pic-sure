package edu.harvard.hms.dbmi.avillach.gateway.health;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * Proves {@link DownstreamHealthContributor} surfaces each monitored downstream as its own health component, driven by real HTTP probes
 * (via WireMock) through the real Spring context -- an up downstream reports UP, a down downstream reports DOWN.
 */
@SpringBootTest
class ActuatorHealthIT {

    static WireMockServer hpds;
    static WireMockServer psama;

    @Autowired
    DownstreamHealthContributor contributor;

    @BeforeAll
    static void start() {
        hpds = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        psama = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        hpds.start();
        psama.start();
        hpds.stubFor(get(urlEqualTo("/actuator/health")).willReturn(okJson("{\"status\":\"UP\"}")));
        psama.stubFor(get(urlEqualTo("/actuator/health")).willReturn(aResponse().withStatus(503)));
    }

    @AfterAll
    static void stop() {
        hpds.stop();
        psama.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("picsure.gateway.health.downstreams[0].name", () -> "hpds");
        r.add("picsure.gateway.health.downstreams[0].base-url", () -> "http://localhost:" + hpds.port());
        r.add("picsure.gateway.health.downstreams[0].require-status-up", () -> "true");
        r.add("picsure.gateway.health.downstreams[1].name", () -> "psama");
        r.add("picsure.gateway.health.downstreams[1].base-url", () -> "http://localhost:" + psama.port());
        r.add("picsure.gateway.health.downstreams[1].require-status-up", () -> "true");
    }

    @Test
    void exposesDownstreamAsHealthComponent() {
        HealthContributor c = contributor.getContributor("hpds");
        assertThat(c).isInstanceOf(HealthIndicator.class);
        assertThat(((HealthIndicator) c).health().getStatus().getCode()).isEqualTo("UP");
    }

    @Test
    void exposesDownDownstreamAsDown() {
        HealthContributor c = contributor.getContributor("psama");
        assertThat(c).isInstanceOf(HealthIndicator.class);
        assertThat(((HealthIndicator) c).health().getStatus().getCode()).isEqualTo("DOWN");
    }
}
