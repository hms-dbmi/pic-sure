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
 * (auth/open) are probed via a short GET to {@code {origin(base)}{healthPath}} (HPDS Actuator is at the host root, not under /PIC-SURE); UP
 * only when every distinct base responds 2xx.
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
        hpds.stubFor(get(urlEqualTo("/actuator/health")).willReturn(aResponse().withStatus(200)));

        Health h = new HpdsHealthIndicator(props(), RestClient.builder().build()).health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void downWhenHpdsErrors() {
        hpds.resetAll();
        hpds.stubFor(get(urlEqualTo("/actuator/health")).willReturn(aResponse().withStatus(503)));

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
        hpds.stubFor(get(urlEqualTo("/actuator/health")).willReturn(aResponse().withStatus(200)));
        HpdsProperties p = props(); // authUrl == openUrl

        Health h = new HpdsHealthIndicator(p, RestClient.builder().build()).health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).hasSize(1); // one distinct base, not two
    }

    /**
     * The probe must fail fast rather than hang on a black-holed HPDS. Exercises the real {@code @Autowired}-visible constructor (which
     * wires the short, health-specific read timeout onto the probe's {@code RestClient.Builder}) against a WireMock stub that delays its
     * response far longer than that timeout. If the timeout were NOT applied (the pre-fix behavior), this call would block for the full
     * stubbed delay instead of failing at the ~3s read-timeout mark.
     */
    @Test
    void probeFailsFastInsteadOfHangingOnASlowHpds() {
        hpds.resetAll();
        hpds.stubFor(get(urlEqualTo("/actuator/health")).willReturn(aResponse().withStatus(200).withFixedDelay(15_000)));

        long start = System.nanoTime();
        Health h = new HpdsHealthIndicator(props(), RestClient.builder()).health(); // real ctor: builder gets timeout-bound requestFactory
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(elapsedMs).isLessThan(10_000); // well under the 15s stub delay -- the read timeout fired, the probe did not wait it out
    }
}
