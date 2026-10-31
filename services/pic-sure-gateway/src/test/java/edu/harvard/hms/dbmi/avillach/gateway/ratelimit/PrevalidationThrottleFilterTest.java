package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.net.CidrRange;
import edu.harvard.hms.dbmi.avillach.commons.net.ClientIpResolver;
import edu.harvard.hms.dbmi.avillach.gateway.auth.OpenAccessRequests;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PublicEndpointPolicy;
import edu.harvard.hms.dbmi.avillach.gateway.auth.ShippedPublicRoutes;
import edu.harvard.hms.dbmi.avillach.gateway.filter.OpenAccessFilter;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitDecision.Window;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitProperties.IpLimits;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitProperties.KeyLimits;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitProperties.Store;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Stage 1 in front of the real {@link OpenAccessFilter} and {@link PsamaClient}, against a WireMock PSAMA. */
class PrevalidationThrottleFilterTest {

    private static final int LIMIT = 3;
    private static final String PROXY = "192.0.2.10";
    private static final String CLIENT_A = "203.0.113.7";
    private static final String CLIENT_B = "198.51.100.8";
    private static final String ALLOWLISTED = "203.0.113.200";

    static WireMockServer psama;

    private final FakeClock clock = new FakeClock("2026-10-29T10:00:30Z");
    private final List<RateLimitSubject> consumed = new ArrayList<>();

    @BeforeAll
    static void start() {
        psama = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        psama.start();
    }

    @AfterAll
    static void stop() {
        psama.stop();
    }

    @BeforeEach
    void stubPsama() {
        psama.resetAll();
        psama.stubFor(post(urlEqualTo("/open/validate")).willReturn(okJson("true")));
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /** Stands in for the proxied route: anything that gets past the filters is answered 200. */
    private static final HttpServlet UPSTREAM = new HttpServlet() {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) {}
    };

    private record Gateway(PrevalidationThrottleFilter throttle, OpenAccessFilter openAccess) {

        MockHttpServletResponse send(MockHttpServletRequest request) throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();
            new MockFilterChain(UPSTREAM, throttle, openAccess).doFilter(request, response);
            return response;
        }
    }

    private Gateway gateway(boolean rateLimitEnabled, boolean openAccessEnabled) {
        RateLimitProperties props = new RateLimitProperties(
            rateLimitEnabled, new KeyLimits(60, 3000), new KeyLimits(60, 3000), new IpLimits(60, 3000, LIMIT), new Store(1000),
            List.of(PROXY + "/32"), List.of(ALLOWLISTED + "/32", PROXY + "/32")
        );
        InMemoryRateLimitStore real = new InMemoryRateLimitStore(props, new SimpleMeterRegistry(), clock);
        RateLimitStore recording = subject -> {
            consumed.add(subject);
            return real.tryConsume(subject);
        };
        PublicEndpointPolicy publicEndpoints = new PublicEndpointPolicy(ShippedPublicRoutes.routes());
        ClientIpResolver resolver = new ClientIpResolver(props.trustedProxies().stream().map(CidrRange::parse).toList());
        PsamaClient client = new PsamaClient(
            RestClient.builder().build(), "http://127.0.0.1:" + psama.port() + "/token/introspect",
            "http://127.0.0.1:" + psama.port() + "/open/validate", "service-token"
        );
        return new Gateway(
            new PrevalidationThrottleFilter(props, new OpenAccessRequests(openAccessEnabled, publicEndpoints), resolver, recording),
            new OpenAccessFilter(client, new AuditContext(), openAccessEnabled, publicEndpoints)
        );
    }

    private Gateway gateway() {
        return gateway(true, true);
    }

    private static MockHttpServletRequest direct(String clientIp) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        request.setRemoteAddr(clientIp);
        return request;
    }

    private static MockHttpServletRequest viaProxy(String clientIp) {
        MockHttpServletRequest request = direct(PROXY);
        request.addHeader("X-Forwarded-For", clientIp);
        return request;
    }

    private static MockHttpServletRequest withAuthorization(MockHttpServletRequest request, String authorization) {
        request.addHeader("Authorization", authorization);
        return request;
    }

    /** Fresh requests each time: {@code OncePerRequestFilter} skips a request object it has already seen. */
    private static void assertPassed(Gateway gateway, Supplier<MockHttpServletRequest> request, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            assertThat(gateway.send(request.get()).getStatus()).as("request %d", i + 1).isEqualTo(200);
        }
    }

    private static void assertThrottled(MockHttpServletResponse response) {
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotBlank();
    }

    private static int psamaCalls() {
        return psama.countRequestsMatching(postRequestedFor(urlEqualTo("/open/validate")).build()).getCount();
    }

    @Test
    void underTheLimitRequestsReachOpenAccessAndPsama() throws Exception {
        Gateway gateway = gateway();

        assertPassed(gateway, () -> direct(CLIENT_A), LIMIT);

        assertThat(psamaCalls()).isEqualTo(LIMIT);
        assertThat(consumed).hasSize(LIMIT);
    }

    @Test
    void overTheLimitAnswers429WithRetryAfterAndTheStandardBodyWithoutCallingPsama() throws Exception {
        Gateway gateway = gateway();
        MDC.put("requestId", "req-1");
        assertPassed(gateway, () -> direct(CLIENT_A), LIMIT);
        psama.resetRequests();

        MockHttpServletRequest request = direct(CLIENT_A);
        MockHttpServletResponse response = gateway.send(request);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("30"); // the clock is 30 s into the minute
        assertThat(response.getContentType()).startsWith("application/json");
        JsonNode body = new ObjectMapper().readTree(response.getContentAsString());
        assertThat(body.path("errorType").asText()).isEqualTo("rate_limited");
        assertThat(body.path("message").asText()).isNotBlank();
        assertThat(body.path("requestId").asText()).isEqualTo("req-1");
        assertThat(psamaCalls()).isZero();
        assertThat(request.getAttribute(RateLimitBreach.REQUEST_ATTRIBUTE)).isEqualTo(
            new RateLimitBreach(
                RateLimitSubject.prevalidationIp(InetAddress.getByName(CLIENT_A)), new RateLimitDecision(false, Window.BURST, 30)
            )
        );
    }

    @Test
    void theBudgetRefillsOnTheNextMinute() throws Exception {
        Gateway gateway = gateway();
        assertPassed(gateway, () -> direct(CLIENT_A), LIMIT);
        assertThrottled(gateway.send(direct(CLIENT_A)));

        clock.set("2026-10-29T10:01:00Z");

        assertPassed(gateway, () -> direct(CLIENT_A), LIMIT);
    }

    @Test
    void clientsBehindTheTrustedProxyHaveIndependentBudgets() throws Exception {
        Gateway gateway = gateway();
        assertPassed(gateway, () -> viaProxy(CLIENT_A), LIMIT);
        assertThrottled(gateway.send(viaProxy(CLIENT_A)));

        assertPassed(gateway, () -> viaProxy(CLIENT_B), LIMIT);
    }

    @Test
    void ipv6AddressesInOneSlash64ShareABudgetAndAnotherSlash64DoesNot() throws Exception {
        Gateway gateway = gateway();
        assertPassed(gateway, () -> viaProxy("2001:db8:1:2::a"), LIMIT);

        assertThrottled(gateway.send(viaProxy("2001:db8:1:2:ffff::b")));
        assertPassed(gateway, () -> viaProxy("2001:db8:1:3::a"), LIMIT);
    }

    @Test
    void anAllowlistedSourceIsNeverThrottledOrCounted() throws Exception {
        Gateway gateway = gateway();

        assertPassed(gateway, () -> viaProxy(ALLOWLISTED), LIMIT * 10);

        assertThat(psamaCalls()).isEqualTo(LIMIT * 10);
        assertThat(consumed).isEmpty();
    }

    @Test
    void aFailedResolutionIsThrottledEvenWhenItFallsBackToAnAllowlistedPeer() throws Exception {
        // The proxy's own address is on the allowlist here; a malformed chain falls back to it but must not earn the exemption.
        Gateway gateway = gateway();
        assertPassed(gateway, () -> viaProxy("not-an-ip"), LIMIT);
        assertThrottled(gateway.send(viaProxy("not-an-ip")));
    }

    @Test
    void aClaimedPlatformKeyFromANonAllowlistedSourceIsThrottledAndTheKeyIsNotTheSubject() throws Exception {
        Gateway gateway = gateway();
        for (int i = 0; i < LIMIT; i++) {
            assertThat(gateway.send(withAuthorization(viaProxy(CLIENT_A), "Bearer picsure_p_claimed" + i)).getStatus()).isEqualTo(200);
        }
        psama.resetRequests();

        assertThrottled(gateway.send(withAuthorization(viaProxy(CLIENT_A), "Bearer picsure_p_another")));
        assertThrottled(gateway.send(withAuthorization(viaProxy(CLIENT_A), "Bearer picsure_s_session")));
        assertThat(psamaCalls()).isZero();
    }

    @Test
    void loginTokenRequestsAreNotCounted() throws Exception {
        Gateway gateway = gateway();

        assertPassed(gateway, () -> withAuthorization(direct(CLIENT_A), "Bearer eyJhbGciOiJIUzI1NiJ9.login.token"), LIMIT * 3);

        assertThat(consumed).isEmpty();
        assertThat(psamaCalls()).isZero();
        assertPassed(gateway, () -> direct(CLIENT_A), LIMIT);
    }

    @Test
    void publicEndpointRequestsAreNotCounted() throws Exception {
        Gateway gateway = gateway();
        assertPassed(gateway, () -> {
            MockHttpServletRequest status = new MockHttpServletRequest("GET", "/system/status");
            status.setRemoteAddr(CLIENT_A);
            return status;
        }, LIMIT * 3);

        assertThat(consumed).isEmpty();
        assertThat(psamaCalls()).isZero();
    }

    @Test
    void aKeyPresentedWhileOpenAccessIsDisabledIsNotCounted() throws Exception {
        Gateway gateway = gateway(true, false);

        for (int i = 0; i < LIMIT * 3; i++) {
            MockHttpServletResponse response = gateway.send(withAuthorization(direct(CLIENT_A), "Bearer picsure_u_someKey"));
            assertThat(response.getStatus()).isEqualTo(401);
        }

        assertThat(consumed).isEmpty();
        assertThat(psamaCalls()).isZero();
    }

    @Test
    void withRateLimitingDisabledTheFilterIsInert() throws Exception {
        Gateway gateway = gateway(false, true);

        assertPassed(gateway, () -> direct(CLIENT_A), LIMIT * 3);

        assertThat(consumed).isEmpty();
        assertThat(psamaCalls()).isEqualTo(LIMIT * 3);
    }
}
