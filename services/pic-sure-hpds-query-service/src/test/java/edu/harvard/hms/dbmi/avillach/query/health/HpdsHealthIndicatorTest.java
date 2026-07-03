package edu.harvard.hms.dbmi.avillach.query.health;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;

import edu.harvard.hms.dbmi.avillach.query.config.HpdsProperties;

/**
 * DB-free deep health: this service owns no DataSource, so its only real dependency to probe is HPDS reachability. Both configured backends
 * (auth/open) are probed via a short GET to {@code {base}{healthPath}}; UP only when every distinct base responds 2xx.
 */
class HpdsHealthIndicatorTest {

    static WireMockServer hpds;

    @BeforeAll
    static void start() {
        hpds = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        hpds.start();
    }

    @AfterAll
    static void stop() {
        hpds.stop();
    }

    private HpdsProperties props() {
        HpdsProperties p = new HpdsProperties();
        String base = "http://localhost:" + hpds.port() + "/PIC-SURE";
        p.setAuthUrl(base);
        p.setOpenUrl(base);
        p.setHealthPath("/actuator/health");
        return p;
    }

    @Test
    void upWhenHpdsResponds() {
        hpds.stubFor(get(urlEqualTo("/PIC-SURE/actuator/health")).willReturn(aResponse().withStatus(200)));

        Health h = new HpdsHealthIndicator(props(), RestClient.builder().build()).health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void downWhenHpdsErrors() {
        hpds.resetAll();
        hpds.stubFor(get(urlEqualTo("/PIC-SURE/actuator/health")).willReturn(aResponse().withStatus(503)));

        Health h = new HpdsHealthIndicator(props(), RestClient.builder().build()).health();

        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void downWhenHpdsUnreachable() {
        HpdsProperties p = props(); // capture the (dynamic) port while the server is still up
        hpds.stop(); // then simulate connection failure

        try {
            Health h = new HpdsHealthIndicator(p, RestClient.builder().build()).health();
            assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        } finally {
            hpds.start(); // restart unconditionally so sibling tests (shared static server) aren't left stranded
        }
    }

    @Test
    void dedupesIdenticalAuthAndOpenBases() {
        hpds.stubFor(get(urlEqualTo("/PIC-SURE/actuator/health")).willReturn(aResponse().withStatus(200)));
        HpdsProperties p = props(); // authUrl == openUrl

        Health h = new HpdsHealthIndicator(p, RestClient.builder().build()).health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).hasSize(1); // one distinct base, not two
    }
}
