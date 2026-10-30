package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/**
 * Stage 2 behind the real {@link OpenAccessFilter} and {@link PsamaClient}, against a WireMock PSAMA that reports key identity per
 * presented key. Each subject kind has its own burst limit, so a test can tell which limits a request drew from.
 */
class PerKeyRateLimitFilterTest {

    private static final int USER_BURST = 3;
    private static final int USER_DAILY = 5;
    private static final int SESSION_BURST = 4;
    private static final int IP_BURST = 2;

    private static final String PROXY = "192.0.2.10";
    private static final String CLIENT_A = "203.0.113.7";
    private static final String CLIENT_B = "198.51.100.8";
    private static final String ALLOWLISTED = "203.0.113.200";

    private static final UUID ALICE_ID = UUID.fromString("7c5e0618-0000-4000-8000-00000000000a");
    private static final UUID BOB_ID = UUID.fromString("7c5e0618-0000-4000-8000-00000000000b");
    private static final UUID PLATFORM_ID = UUID.fromString("7c5e0618-0000-4000-8000-0000000000ff");

    private static final String ALICE = "picsure_u_alice";
    private static final String BOB = "picsure_u_bob";
    private static final String PLATFORM = "picsure_p_platform";
    private static final String FORGED_PLATFORM = "picsure_p_forged";
    private static final String SESSION_ONE = "picsure_s_one";
    private static final String SESSION_TWO = "picsure_s_two";
    private static final String SESSION_DUE = "picsure_s_due";
    private static final String SESSION_DUE_REFRESHED = "picsure_s_due_refreshed";
    /** A session whose {@code sub} is the same string as Alice's key UUID. */
    private static final String SESSION_LIKE_ALICE = "picsure_s_like_alice";
    private static final String OLD_PSAMA_KEY = "picsure_u_old_psama";

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
        psama.stubFor(post(urlEqualTo("/open/validate")).atPriority(10).willReturn(okJson(granted(null, null, null))));
        stubKey(ALICE, granted("USER", ALICE_ID.toString(), null));
        stubKey(BOB, granted("USER", BOB_ID.toString(), null));
        stubKey(PLATFORM, granted("PLATFORM", PLATFORM_ID.toString(), null));
        stubKey(FORGED_PLATFORM, "{\"valid\":false,\"keyType\":null,\"keyId\":null,\"denial\":\"key_invalid\"}");
        stubKey(SESSION_ONE, granted("SESSION", "sub-one", null));
        stubKey(SESSION_TWO, granted("SESSION", "sub-two", null));
        stubKey(SESSION_DUE, granted("SESSION", "sub-due", SESSION_DUE_REFRESHED));
        stubKey(SESSION_DUE_REFRESHED, granted("SESSION", "sub-due", null));
        stubKey(SESSION_LIKE_ALICE, granted("SESSION", ALICE_ID.toString(), null));
        stubKey(OLD_PSAMA_KEY, "true");
    }

    private static String granted(String keyType, String keyId, String refreshedToken) {
        return "{\"valid\":true,\"keyType\":%s,\"keyId\":%s,\"displayPrefix\":null,\"denial\":null,\"refreshedToken\":%s}"
            .formatted(quoted(keyType), quoted(keyId), quoted(refreshedToken));
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static void stubKey(String key, String responseJson) {
        psama.stubFor(
            post(urlEqualTo("/open/validate")).atPriority(1).withRequestBody(matchingJsonPath("$.apiKey", equalTo(key)))
                .willReturn(okJson(responseJson))
        );
    }

    /** Stands in for the proxied route: anything that gets past the filters is answered 200. */
    private static final HttpServlet UPSTREAM = new HttpServlet() {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) {}
    };

    private record Gateway(OpenAccessFilter openAccess, PerKeyRateLimitFilter perKey) {

        MockHttpServletResponse send(MockHttpServletRequest request) throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();
            new MockFilterChain(UPSTREAM, openAccess, perKey).doFilter(request, response);
            return response;
        }
    }

    private Gateway gateway(boolean rateLimitEnabled) {
        RateLimitProperties props = new RateLimitProperties(
            rateLimitEnabled, new KeyLimits(USER_BURST, USER_DAILY), new KeyLimits(SESSION_BURST, 3000), new IpLimits(IP_BURST, 3000, 300),
            new Store(1000), List.of(PROXY + "/32"), List.of(ALLOWLISTED + "/32", PROXY + "/32")
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
            new OpenAccessFilter(client, new AuditContext(), true, publicEndpoints), new PerKeyRateLimitFilter(props, resolver, recording)
        );
    }

    private Gateway gateway() {
        return gateway(true);
    }

    private static MockHttpServletRequest from(String clientIp) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        request.setRemoteAddr(PROXY);
        request.addHeader("X-Forwarded-For", clientIp);
        return request;
    }

    private static MockHttpServletRequest withKey(String key, String clientIp) {
        MockHttpServletRequest request = from(clientIp);
        request.addHeader("Authorization", "Bearer " + key);
        return request;
    }

    /** Fresh requests each time: {@code OncePerRequestFilter} skips a request object it has already seen. */
    private static void assertPassed(Gateway gateway, Supplier<MockHttpServletRequest> request, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            assertThat(gateway.send(request.get()).getStatus()).as("request %d", i + 1).isEqualTo(200);
        }
    }

    private static MockHttpServletResponse assertLimited(Gateway gateway, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = gateway.send(request);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotBlank();
        return response;
    }

    private static InetAddress address(String ip) throws Exception {
        return InetAddress.getByName(ip);
    }

    @Test
    void aUserKeyDrawsFromItsKeyUuidWithUserLimits() throws Exception {
        Gateway gateway = gateway();
        assertPassed(gateway, () -> withKey(ALICE, CLIENT_A), USER_BURST);

        MockHttpServletRequest request = withKey(ALICE, CLIENT_A);
        MockHttpServletResponse response = assertLimited(gateway, request);

        assertThat(consumed).containsOnly(RateLimitSubject.user(ALICE_ID));
        assertThat(response.getHeader("Retry-After")).isEqualTo("30"); // the clock is 30 s into the minute
        assertThat(response.getContentType()).startsWith("application/json");
        JsonNode body = new ObjectMapper().readTree(response.getContentAsString());
        assertThat(body.path("errorType").asText()).isEqualTo(RateLimitBreach.ERROR_TYPE);
        assertThat(request.getAttribute(RateLimitBreach.REQUEST_ATTRIBUTE))
            .isEqualTo(new RateLimitBreach(RateLimitSubject.user(ALICE_ID), new RateLimitDecision(false, Window.BURST, 30)));
    }

    @Test
    void aSessionDrawsFromItsSubWithSessionLimits() throws Exception {
        Gateway gateway = gateway();
        assertPassed(gateway, () -> withKey(SESSION_ONE, CLIENT_A), SESSION_BURST);

        assertLimited(gateway, withKey(SESSION_ONE, CLIENT_A));

        assertThat(consumed).containsOnly(RateLimitSubject.session("sub-one"));
    }

    @Test
    void anAllowlistedPlatformKeyIsExemptAndNeverCounted() throws Exception {
        Gateway gateway = gateway();

        assertPassed(gateway, () -> withKey(PLATFORM, ALLOWLISTED), USER_BURST * 10);

        assertThat(consumed).isEmpty();
    }

    @Test
    void aValidPlatformKeyFromANonAllowlistedSourceDrawsFromTheClientIpWithIpLimits() throws Exception {
        Gateway gateway = gateway();
        assertPassed(gateway, () -> withKey(PLATFORM, CLIENT_A), IP_BURST);

        assertLimited(gateway, withKey(PLATFORM, CLIENT_A));

        assertThat(consumed).containsOnly(RateLimitSubject.ip(address(CLIENT_A)));
    }

    @Test
    void aPlatformKeyWithAFailedIpResolutionIsNotExemptEvenWhenItFallsBackToAnAllowlistedPeer() throws Exception {
        // The proxy itself is on the allowlist here; a malformed chain falls back to it but must not earn the exemption.
        Gateway gateway = gateway();
        assertPassed(gateway, () -> withKey(PLATFORM, "not-an-ip"), IP_BURST);

        assertLimited(gateway, withKey(PLATFORM, "not-an-ip"));
    }

    @Test
    void aGrantWithNoKeyDrawsFromTheClientIpByIpv6Slash64() throws Exception {
        Gateway gateway = gateway();
        assertPassed(gateway, () -> from("2001:db8:1:2::a"), IP_BURST);

        assertLimited(gateway, from("2001:db8:1:2:ffff::b"));
        assertPassed(gateway, () -> from("2001:db8:1:3::a"), IP_BURST);
        assertThat(consumed).contains(RateLimitSubject.ip(address("2001:db8:1:2::a")), RateLimitSubject.ip(address("2001:db8:1:3::a")));
    }

    @Test
    void aBareTrueGrantFromAnOlderPsamaDrawsFromTheClientIp() throws Exception {
        Gateway gateway = gateway();
        assertPassed(gateway, () -> withKey(OLD_PSAMA_KEY, CLIENT_A), IP_BURST);

        assertLimited(gateway, withKey(OLD_PSAMA_KEY, CLIENT_A));

        assertThat(consumed).containsOnly(RateLimitSubject.ip(address(CLIENT_A)));
    }

    @Test
    void aPlatformPrefixedKeyPsamaRejectsGets401AndTouchesNoBucket() throws Exception {
        Gateway gateway = gateway();

        for (int i = 0; i < USER_BURST * 3; i++) {
            assertThat(gateway.send(withKey(FORGED_PLATFORM, ALLOWLISTED)).getStatus()).isEqualTo(401);
        }

        assertThat(consumed).isEmpty();
    }

    @Test
    void sessionsAreNeverExemptOrIpBucketedEvenFromAnAllowlistedSource() throws Exception {
        Gateway gateway = gateway();
        assertPassed(gateway, () -> withKey(SESSION_ONE, ALLOWLISTED), SESSION_BURST);

        assertLimited(gateway, withKey(SESSION_ONE, ALLOWLISTED));

        assertThat(consumed).containsOnly(RateLimitSubject.session("sub-one"));
    }

    @Test
    void twoUserKeysFromOneIpHaveIndependentBudgets() throws Exception {
        Gateway gateway = gateway();
        assertPassed(gateway, () -> withKey(ALICE, CLIENT_A), USER_BURST);
        assertLimited(gateway, withKey(ALICE, CLIENT_A));

        assertPassed(gateway, () -> withKey(BOB, CLIENT_A), USER_BURST);
    }

    @Test
    void oneUserKeyFromTwoIpsSharesOneBudget() throws Exception {
        Gateway gateway = gateway();
        assertPassed(gateway, () -> withKey(ALICE, CLIENT_A), USER_BURST - 1);
        assertPassed(gateway, () -> withKey(ALICE, CLIENT_B), 1);

        assertLimited(gateway, withKey(ALICE, CLIENT_B));
        assertLimited(gateway, withKey(ALICE, CLIENT_A));
    }

    @Test
    void twoSessionsFromOneIpHaveIndependentBudgets() throws Exception {
        Gateway gateway = gateway();
        assertPassed(gateway, () -> withKey(SESSION_ONE, CLIENT_A), SESSION_BURST);
        assertLimited(gateway, withKey(SESSION_ONE, CLIENT_A));

        assertPassed(gateway, () -> withKey(SESSION_TWO, CLIENT_A), SESSION_BURST);
    }

    @Test
    void aRefreshedSessionTokenWithTheSameSubKeepsItsBucket() throws Exception {
        Gateway gateway = gateway();
        assertPassed(gateway, () -> withKey(SESSION_DUE, CLIENT_A), SESSION_BURST - 1);
        assertPassed(gateway, () -> withKey(SESSION_DUE_REFRESHED, CLIENT_A), 1);

        assertLimited(gateway, withKey(SESSION_DUE_REFRESHED, CLIENT_A));
    }

    @Test
    void aLimitedSessionDueARefreshStillGetsTheRefreshHeader() throws Exception {
        Gateway gateway = gateway();
        assertPassed(gateway, () -> withKey(SESSION_DUE, CLIENT_A), SESSION_BURST);

        MockHttpServletResponse response = assertLimited(gateway, withKey(SESSION_DUE, CLIENT_A));

        assertThat(response.getHeader(OpenAccessFilter.SESSION_REFRESH_HEADER)).isEqualTo(SESSION_DUE_REFRESHED);
    }

    @Test
    void aDailyBreachRetriesAfterMidnightUtc() throws Exception {
        Gateway gateway = gateway();
        assertPassed(gateway, () -> withKey(ALICE, CLIENT_A), USER_BURST);
        clock.set("2026-10-29T10:01:00Z");
        assertPassed(gateway, () -> withKey(ALICE, CLIENT_A), USER_DAILY - USER_BURST);

        MockHttpServletRequest request = withKey(ALICE, CLIENT_A);
        MockHttpServletResponse response = assertLimited(gateway, request);

        long untilMidnight = 13 * 3600 + 59 * 60;
        assertThat(response.getHeader("Retry-After")).isEqualTo(Long.toString(untilMidnight));
        assertThat(request.getAttribute(RateLimitBreach.REQUEST_ATTRIBUTE))
            .isEqualTo(new RateLimitBreach(RateLimitSubject.user(ALICE_ID), new RateLimitDecision(false, Window.SUSTAINED, untilMidnight)));
    }

    @Test
    void aUserKeyAndASessionWithEqualIdsDoNotShareABucket() throws Exception {
        Gateway gateway = gateway();
        assertPassed(gateway, () -> withKey(ALICE, CLIENT_A), USER_BURST);
        assertLimited(gateway, withKey(ALICE, CLIENT_A));

        assertPassed(gateway, () -> withKey(SESSION_LIKE_ALICE, CLIENT_A), SESSION_BURST);
    }

    @Test
    void loginTokenRequestsPassUntouched() throws Exception {
        Gateway gateway = gateway();

        assertPassed(gateway, () -> withKey("eyJhbGciOiJIUzI1NiJ9.login.token", CLIENT_A), IP_BURST * 5);

        assertThat(consumed).isEmpty();
    }

    @Test
    void publicEndpointRequestsPassUntouched() throws Exception {
        Gateway gateway = gateway();

        assertPassed(gateway, () -> {
            MockHttpServletRequest status = new MockHttpServletRequest("GET", "/system/status");
            status.setRemoteAddr(CLIENT_A);
            return status;
        }, IP_BURST * 5);

        assertThat(consumed).isEmpty();
    }

    @Test
    void withRateLimitingDisabledTheFilterIsInert() throws Exception {
        Gateway gateway = gateway(false);

        assertPassed(gateway, () -> withKey(ALICE, CLIENT_A), USER_BURST * 3);
        assertPassed(gateway, () -> from(CLIENT_A), IP_BURST * 3);

        assertThat(consumed).isEmpty();
    }
}
