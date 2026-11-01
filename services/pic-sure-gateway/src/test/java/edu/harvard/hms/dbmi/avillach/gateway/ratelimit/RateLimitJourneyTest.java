package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.gateway.filter.OpenAccessFilter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Every limiter journey through the real gateway: full context, limiter on, real HTTP on the random port, WireMock for PSAMA and for
 * operations-service. The production filters, {@code ClientIpResolver} and allowlist are used as registered; only the store is swapped for
 * the same {@link InMemoryRateLimitStore} on a {@link FakeClock}, so no test straddles a minute boundary, and it records which subjects it
 * was asked for. Requests arrive from 127.0.0.1, the trusted proxy, with {@code X-Forwarded-For} set as httpd would append it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitJourneyTest {

    private static final int PREVALIDATION = 5;
    private static final int BURST = 2;

    private static final String ALLOWLISTED = "198.51.100.7";
    private static final String USER_KEY_A = "picsure_u_keyAlpha";
    private static final String USER_KEY_B = "picsure_u_keyBravo";
    private static final String USER_ID_A = "0d0c6b0e-1a2b-4c3d-8e4f-00000000000a";
    private static final String USER_ID_B = "0d0c6b0e-1a2b-4c3d-8e4f-00000000000b";
    private static final String PLATFORM_KEY = "picsure_p_integration";
    private static final String SESSION_TOKEN = "picsure_s_current.session.token";
    private static final String REFRESHED = "picsure_s_refreshed.session.token";
    private static final String VALIDATE = "/auth/open/validate";
    private static final String INSPECT = "/auth/token/inspect";
    private static final String UPSTREAM = "/operations/data";

    static final FakeClock CLOCK = new FakeClock("2026-03-02T12:00:30Z");
    static final List<RateLimitSubject> CONSUMED = new CopyOnWriteArrayList<>();

    static WireMockServer operationsStub;
    static WireMockServer psamaStub;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        operationsStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        operationsStub.start();
        registry.add("OPERATIONS_SERVICE_URL", operationsStub::baseUrl);

        psamaStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        psamaStub.start();
        registry.add("TOKEN_INTROSPECTION_URL", () -> psamaStub.baseUrl() + INSPECT);
        registry.add("OPEN_ACCESS_VALIDATE_URL", () -> psamaStub.baseUrl() + VALIDATE);
        registry.add("GATEWAY_OPEN_ACCESS_ENABLED", () -> "true");

        registry.add("RATE_LIMIT_ENABLED", () -> "true");
        registry.add("RATE_LIMIT_TRUSTED_PROXIES", () -> "127.0.0.1");
        registry.add("RATE_LIMIT_PLATFORM_ALLOWLISTED_SOURCE_CIDRS", () -> "198.51.100.0/24");
        registry.add("RATE_LIMIT_IP_PREVALIDATION_PER_MINUTE", () -> PREVALIDATION);
        for (String tier : List.of("USER", "SESSION", "IP")) {
            registry.add("RATE_LIMIT_" + tier + "_BURST_PER_MINUTE", () -> BURST);
            registry.add("RATE_LIMIT_" + tier + "_SUSTAINED_PER_DAY", () -> 100);
        }
    }

    /** The production store on {@link #CLOCK}, recording each subject it is asked to charge. */
    @TestConfiguration
    static class FakeClockStore {

        @Bean
        @Primary
        RateLimitStore fakeClockRateLimitStore(RateLimitProperties props, MeterRegistry meterRegistry) {
            RateLimitStore store = new InMemoryRateLimitStore(props, meterRegistry, CLOCK);
            return subject -> {
                CONSUMED.add(subject);
                return store.tryConsume(subject);
            };
        }
    }

    @AfterAll
    static void stopStubs() {
        operationsStub.stop();
        psamaStub.stop();
    }

    @BeforeEach
    void reset() {
        // a new day, 30s into a minute: every bucket from an earlier test is full again, and Retry-After for a burst is exactly 30
        CLOCK.advance(Duration.ofDays(1));
        CONSUMED.clear();
        operationsStub.resetAll();
        operationsStub.stubFor(get(urlEqualTo(UPSTREAM)).willReturn(aResponse().withStatus(200).withBody("upstream")));
        psamaStub.resetAll();
    }

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    int port;

    // Journey (a): anonymous, no token

    @Test
    void anonymousRequestsReachUpstreamThenStageTwoThenStageOneLimitsThem() {
        grant(validate(), null, null, null);
        String client = "203.0.113.10";

        assertReachUpstream(BURST, null, client);
        // granted by PSAMA, then refused by the client IP's post-grant bucket
        for (int i = BURST; i < PREVALIDATION; i++) {
            assertRateLimited(send(null, client));
        }
        psamaStub.verify(PREVALIDATION, postRequestedFor(urlEqualTo(VALIDATE)));

        // over stage 1: refused before PSAMA is called
        assertRateLimited(send(null, client));
        psamaStub.verify(PREVALIDATION, postRequestedFor(urlEqualTo(VALIDATE)));
        operationsStub.verify(BURST, getRequestedFor(urlEqualTo(UPSTREAM)));
        assertThat(CONSUMED).filteredOn(s -> s.kind() == RateLimitSubject.Kind.IP).extracting(RateLimitSubject::value).containsOnly(client);
    }

    // Journey (b): USER keys

    @Test
    void aUserKeyReachesUpstreamAsOpenAccessUntilItsOwnBucketIsSpent() {
        grant(validate().withRequestBody(matchingJsonPath("$.apiKey", equalTo(USER_KEY_A))), "USER", USER_ID_A, null);
        String client = "203.0.113.20";

        assertReachUpstream(BURST, USER_KEY_A, client);
        operationsStub.verify(
            BURST,
            getRequestedFor(urlEqualTo(UPSTREAM))
                .withHeader(GatewayUserResolver.HEADER_ACCESS_TYPE, equalTo(GatewayUserResolver.ACCESS_TYPE_OPEN))
                .withHeader(HttpHeaders.AUTHORIZATION, absent())
        );

        assertRateLimited(send(USER_KEY_A, client));
        assertThat(CONSUMED).contains(RateLimitSubject.user(UUID.fromString(USER_ID_A)));
    }

    @Test
    void twoUserKeysFromOneIpHaveIndependentBudgets() {
        grant(validate().withRequestBody(matchingJsonPath("$.apiKey", equalTo(USER_KEY_A))), "USER", USER_ID_A, null);
        grant(validate().withRequestBody(matchingJsonPath("$.apiKey", equalTo(USER_KEY_B))), "USER", USER_ID_B, null);
        String client = "203.0.113.21";

        assertReachUpstream(BURST, USER_KEY_A, client);
        assertRateLimited(send(USER_KEY_A, client));

        assertReachUpstream(BURST, USER_KEY_B, client);
    }

    // Journey (c): PLATFORM keys

    @Test
    void aPlatformKeyFromAnAllowlistedSourceIsNeverLimited() {
        grant(validate(), "PLATFORM", "0d0c6b0e-1a2b-4c3d-8e4f-0000000000c0", "picsure_p_inte");

        // well past both the stage-1 and the stage-2 limits
        assertReachUpstream(PREVALIDATION + BURST + 1, PLATFORM_KEY, ALLOWLISTED);
        assertThat(CONSUMED).isEmpty();
    }

    @Test
    void aPlatformKeyFromANonAllowlistedSourceIsLimitedByItsClientIp() {
        grant(validate(), "PLATFORM", "0d0c6b0e-1a2b-4c3d-8e4f-0000000000c0", "picsure_p_inte");
        String client = "203.0.113.30";

        assertReachUpstream(BURST, PLATFORM_KEY, client);
        assertRateLimited(send(PLATFORM_KEY, client));
        assertThat(CONSUMED).filteredOn(s -> s.kind() == RateLimitSubject.Kind.IP).extracting(RateLimitSubject::value).containsOnly(client);
    }

    // Journey (d): a session due a refresh

    @Test
    void aLimitedSessionDueARefreshStillReceivesTheRefreshedToken() {
        grant(validate(), "SESSION", "7c5e0618-5a3f-4d1b-9f0e-2b6c1d8a4e10", null, REFRESHED);
        String client = "203.0.113.40";

        for (int i = 0; i < BURST; i++) {
            assertThat(send(SESSION_TOKEN, client).getHeaders().getFirst(OpenAccessFilter.SESSION_REFRESH_HEADER)).isEqualTo(REFRESHED);
        }

        ResponseEntity<String> limited = send(SESSION_TOKEN, client);

        assertRateLimited(limited);
        assertThat(limited.getHeaders().getFirst(OpenAccessFilter.SESSION_REFRESH_HEADER)).isEqualTo(REFRESHED);
        assertThat(CONSUMED).filteredOn(s -> s.kind() != RateLimitSubject.Kind.PREVALIDATION_IP)
            .containsOnly(RateLimitSubject.session("7c5e0618-5a3f-4d1b-9f0e-2b6c1d8a4e10"));
    }

    // Journey (e): a key PSAMA rejects

    @Test
    void aRejectedKeyIs401CountedByStageOneAndNeverGivenAStageTwoBucket() {
        psamaStub.stubFor(
            validate().willReturn(
                okJson(
                    "{\"valid\":false,\"keyType\":null,\"keyId\":null,\"displayPrefix\":null,\"denial\":\"key_invalid\","
                        + "\"refreshedToken\":null}"
                )
            )
        );
        String client = "203.0.113.50";

        for (int i = 0; i < PREVALIDATION; i++) {
            ResponseEntity<String> denied = send("picsure_p_forged", client);
            assertThat(denied.getStatusCode().value()).isEqualTo(401);
            assertThat(denied.getBody()).contains("\"errorType\":\"api_key_invalid\"");
        }
        assertRateLimited(send("picsure_p_forged", client));

        psamaStub.verify(PREVALIDATION, postRequestedFor(urlEqualTo(VALIDATE)));
        assertThat(CONSUMED).hasSize(PREVALIDATION + 1).allMatch(s -> s.kind() == RateLimitSubject.Kind.PREVALIDATION_IP);
        operationsStub.verify(0, getRequestedFor(urlEqualTo(UPSTREAM)));
    }

    // Journey (f): login tokens

    @Test
    void loginTokensAreNeverCountedAndReachIntrospection() {
        psamaStub.stubFor(
            post(urlEqualTo(INSPECT))
                .willReturn(okJson("{\"active\":true,\"userId\":\"u-1\",\"sub\":\"s-1\",\"email\":\"a@b\",\"role\":\"USER\"}"))
        );
        String client = "203.0.113.60";
        int requests = PREVALIDATION + BURST + 1;

        assertReachUpstream(requests, "eyJhbGciOiJIUzI1NiJ9.journey.token", client);
        assertReachUpstream(requests, "picsure_l_longTermToken", client);

        psamaStub.verify(2 * requests, postRequestedFor(urlEqualTo(INSPECT)));
        psamaStub.verify(0, postRequestedFor(urlEqualTo(VALIDATE)));
        assertThat(CONSUMED).isEmpty();
    }

    /** Dial the loopback address, never the name; see {@code DatasetRouteTest#url}. */
    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private static MappingBuilder validate() {
        return post(urlEqualTo(VALIDATE));
    }

    private static void grant(MappingBuilder request, String keyType, String keyId, String displayPrefix) {
        grant(request, keyType, keyId, displayPrefix, null);
    }

    private static void grant(MappingBuilder request, String keyType, String keyId, String displayPrefix, String refreshedToken) {
        psamaStub.stubFor(
            request.willReturn(
                okJson(
                    "{\"valid\":true,\"keyType\":" + json(keyType) + ",\"keyId\":" + json(keyId) + ",\"displayPrefix\":"
                        + json(displayPrefix) + ",\"denial\":null,\"refreshedToken\":" + json(refreshedToken) + "}"
                )
            )
        );
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    /** A GET to the upstream route from {@code client}, as httpd forwards it; {@code bearer} null sends no {@code Authorization}. */
    private ResponseEntity<String> send(String bearer, String client) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        headers.set("X-Forwarded-For", client);
        return rest.exchange(url(UPSTREAM), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private void assertReachUpstream(int times, String bearer, String client) {
        for (int i = 0; i < times; i++) {
            ResponseEntity<String> response = send(bearer, client);
            assertThat(response.getStatusCode().value()).as("request %d", i + 1).isEqualTo(200);
            assertThat(response.getBody()).isEqualTo("upstream");
        }
    }

    private static void assertRateLimited(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        assertThat(response.getBody()).contains("\"errorType\":\"rate_limited\"");
    }
}
