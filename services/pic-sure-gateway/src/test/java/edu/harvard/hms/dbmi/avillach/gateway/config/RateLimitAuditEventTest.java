package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.hms.dbmi.avillach.commons.net.ClientIpResolver;
import edu.harvard.hms.dbmi.avillach.gateway.auth.OpenAccessValidation;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitDecision;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitDecision.Window;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitStore;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitSubject;

/**
 * A 429 from either stage, through the registered gateway filters from {@code RequestIdFilter} to the identity-header sanitizer (25) in
 * configured order, sends one audit event to the {@link LoggingClient} that names the breach and never the presented key. The store is
 * stubbed per subject kind, so each test picks which stage breaches. {@code RateLimitJourneyTest} runs the limiter through the whole
 * gateway.
 */
@ExtendWith(OutputCaptureExtension.class)
class RateLimitAuditEventTest {

    private static final String PROXY = "192.0.2.10";
    private static final String CLIENT = "203.0.113.7";
    private static final UUID KEY_ID = UUID.fromString("7c5e0618-0000-4000-8000-00000000000a");
    private static final String USER_KEY = "picsure_u_AbCdEfGh0123456789plaintextSecret";
    private static final String USER_DISPLAY_PREFIX = "picsure_u_AbCd";
    private static final String SESSION_TOKEN = "picsure_s_eyJhbGciOiJIUzI1NiJ9.session.signature";

    private final LoggingClient logging = mock(LoggingClient.class);
    private final PsamaClient psama = mock(PsamaClient.class);
    /** {@code RATE_LIMIT_TRUSTED_PROXIES}. */
    private List<String> trustedProxies = List.of(PROXY + "/32");

    private MockHttpServletResponse send(RateLimitStore store, MockHttpServletRequest request) throws Exception {
        return AuditFilterChain.send(trustedProxies, store, logging, psama, request);
    }

    /** Breaches only subjects of {@code kind}, with {@code decision}; every other subject is allowed. */
    private static RateLimitStore breaching(RateLimitSubject.Kind kind, RateLimitDecision decision) {
        return subject -> subject.kind() == kind ? decision : new RateLimitDecision(true, null, 0);
    }

    private void grant(String keyType, String keyId, String displayPrefix) {
        when(psama.validateOpenAccess(anyMap()))
            .thenReturn(new OpenAccessValidation(true, keyType, keyId, displayPrefix, null, null));
    }

    private static MockHttpServletRequest request(String authorization) {
        return request("/query/sync", authorization);
    }

    private static MockHttpServletRequest request(String path, String authorization) {
        return request(path, authorization, PROXY, CLIENT);
    }

    private static MockHttpServletRequest request(String path, String authorization, String peer, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(peer);
        request.addHeader("X-Forwarded-For", forwardedFor);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    /** What the audit filter handed the {@link LoggingClient}: the event and both headers it forwards to the logging service. */
    private record Sent(LoggingEvent event, String authorization, String requestId) {
    }

    /** The one send. A request id is always present (RequestIdFilter), so the three-argument overload is the one called. */
    private Sent sent() {
        ArgumentCaptor<LoggingEvent> event = ArgumentCaptor.forClass(LoggingEvent.class);
        ArgumentCaptor<String> authorization = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> requestId = ArgumentCaptor.forClass(String.class);
        verify(logging).send(event.capture(), authorization.capture(), requestId.capture());
        return new Sent(event.getValue(), authorization.getValue(), requestId.getValue());
    }

    private LoggingEvent emitted() {
        return sent().event();
    }

    /** Checks every {@code send} argument, not only the event: the authorization argument becomes a header to the logging service. */
    private void assertNeverContains(CapturedOutput output, String secret) throws Exception {
        Sent sent = sent();
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(sent.event())).doesNotContain(secret);
        assertThat(sent.authorization()).isNull();
        assertThat(sent.requestId()).isNotBlank().doesNotContain(secret);
        // the access line proves the gateway logged into the capture; its layout depends on whether Spring configured logback first
        assertThat(output.getAll()).contains("gateway.access").doesNotContain(secret);
    }

    @Test
    void aStageOneBreachIsAuditedAsTheClientIpInThePrevalidationWindow(CapturedOutput output) throws Exception {
        MockHttpServletResponse response = send(
            breaching(RateLimitSubject.Kind.PREVALIDATION_IP, new RateLimitDecision(false, Window.BURST, 12)), request("Bearer " + USER_KEY)
        );

        assertThat(response.getStatus()).isEqualTo(429);
        verifyNoInteractions(psama);
        LoggingEvent event = emitted();
        assertThat(event.getRequest().getStatus()).isEqualTo(429);
        assertThat(event.getRequest().getUrl()).isEqualTo("/query/sync");
        assertThat(event.getRequest().getRequestId()).isNotBlank().isEqualTo(response.getHeader("X-Request-Id"));
        assertThat(event.getMetadata()).containsEntry("auth_result", "failure").containsEntry("auth_action", "open_access.rate_limited")
            .containsEntry("rate_limit_subject_type", "client_ip").containsEntry("rate_limit_subject", CLIENT)
            .containsEntry("rate_limit_window", "prevalidation").containsEntry("rate_limit_retry_after_seconds", 12L)
            .doesNotContainKey("rate_limit_key_display_prefix").doesNotContainKey("rate_limit_ip_resolution_failed");
        assertThat(event.getRequest().getSrcIp()).isEqualTo(CLIENT);
        assertNeverContains(output, USER_KEY);
    }

    @Test
    void aUserKeyBurstBreachIsAuditedWithItsUuidAndDisplayPrefixInsteadOfTheGrant(CapturedOutput output) throws Exception {
        grant(OpenAccessValidation.KEY_TYPE_USER, KEY_ID.toString(), USER_DISPLAY_PREFIX);

        MockHttpServletResponse response =
            send(breaching(RateLimitSubject.Kind.USER, new RateLimitDecision(false, Window.BURST, 30)), request("Bearer " + USER_KEY));

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("30");
        LoggingEvent event = emitted();
        assertThat(event.getRequest().getStatus()).isEqualTo(429);
        assertThat(event.getMetadata()).containsEntry("auth_result", "failure").containsEntry("auth_action", "open_access.rate_limited")
            .containsEntry("auth_failure_reason", "rate_limited").containsEntry("rate_limit_subject_type", "user_key")
            .containsEntry("rate_limit_subject", KEY_ID.toString()).containsEntry("rate_limit_key_display_prefix", USER_DISPLAY_PREFIX)
            .containsEntry("rate_limit_window", "burst").containsEntry("rate_limit_retry_after_seconds", 30L);
        assertNeverContains(output, USER_KEY);
    }

    @Test
    void aUserKeyDailyBreachIsAuditedInTheSustainedWindowWithItsRetryAfter(CapturedOutput output) throws Exception {
        grant(OpenAccessValidation.KEY_TYPE_USER, KEY_ID.toString(), USER_DISPLAY_PREFIX);

        MockHttpServletResponse response = send(
            breaching(RateLimitSubject.Kind.USER, new RateLimitDecision(false, Window.SUSTAINED, 50_400)), request("Bearer " + USER_KEY)
        );

        assertThat(response.getHeader("Retry-After")).isEqualTo("50400");
        assertThat(emitted().getMetadata()).containsEntry("rate_limit_window", "sustained")
            .containsEntry("rate_limit_retry_after_seconds", 50_400L);
        assertNeverContains(output, USER_KEY);
    }

    @Test
    void aSessionBreachIsAuditedByItsSubAndNeverByItsToken(CapturedOutput output) throws Exception {
        grant(OpenAccessValidation.KEY_TYPE_SESSION, "sub-one", null);

        send(breaching(RateLimitSubject.Kind.SESSION, new RateLimitDecision(false, Window.BURST, 30)), request("Bearer " + SESSION_TOKEN));

        LoggingEvent event = emitted();
        assertThat(event.getMetadata()).containsEntry("rate_limit_subject_type", "session").containsEntry("rate_limit_subject", "sub-one");
        assertNeverContains(output, SESSION_TOKEN);
    }

    @Test
    void anIpBucketedBreachOfAVerifiedPlatformKeyCarriesNoKeyFields(CapturedOutput output) throws Exception {
        String platformKey = "picsure_p_ZyXw9876543210plaintextSecret";
        grant(OpenAccessValidation.KEY_TYPE_PLATFORM, KEY_ID.toString(), "picsure_p_ZyXw");

        send(breaching(RateLimitSubject.Kind.IP, new RateLimitDecision(false, Window.BURST, 30)), request("Bearer " + platformKey));

        LoggingEvent event = emitted();
        assertThat(event.getMetadata()).containsEntry("rate_limit_subject_type", "client_ip").containsEntry("rate_limit_subject", CLIENT)
            .containsEntry("rate_limit_window", "burst").doesNotContainKey("rate_limit_key_display_prefix")
            .doesNotContainKey("rate_limit_ip_resolution_failed");
        assertThat(event.getMetadata().values()).doesNotContain(KEY_ID.toString());
        assertNeverContains(output, platformKey);
    }

    @Test
    void aBreachOnAPathTheAuditFilterSkipsIsStillAudited(CapturedOutput output) throws Exception {
        MockHttpServletResponse response = send(
            breaching(RateLimitSubject.Kind.PREVALIDATION_IP, new RateLimitDecision(false, Window.BURST, 12)),
            request("/visualization/bin/continuous", "Bearer " + USER_KEY)
        );

        assertThat(response.getStatus()).isEqualTo(429);
        LoggingEvent event = emitted();
        assertThat(event.getRequest().getUrl()).isEqualTo("/visualization/bin/continuous");
        assertThat(event.getMetadata()).containsEntry("auth_action", "open_access.rate_limited")
            .containsEntry("rate_limit_window", "prevalidation");
        assertNeverContains(output, USER_KEY);
    }

    @Test
    void aGrantedRequestIsStillAuditedAsAGrant(CapturedOutput output) throws Exception {
        grant(OpenAccessValidation.KEY_TYPE_USER, KEY_ID.toString(), USER_DISPLAY_PREFIX);

        MockHttpServletResponse response = send(subject -> new RateLimitDecision(true, null, 0), request("Bearer " + USER_KEY));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(emitted().getMetadata()).containsEntry("auth_action", "open_access.granted").doesNotContainKey("rate_limit_window");
        assertNeverContains(output, USER_KEY);
    }

    /** BDC: caller, ALB (10.0.1.0/24), httpd (192.0.2.10) as peer, and a forged leading entry the caller sent. */
    private static final String ALB = "10.0.1.5";
    private static final String BDC_CHAIN = "6.6.6.6, " + CLIENT + ", " + ALB;

    private void bdcProxies() {
        trustedProxies = List.of(PROXY + "/32", "10.0.1.0/24");
    }

    @Test
    void aGrantOnTheBdcChainRecordsTheResolvedClientAsSrcIp() throws Exception {
        bdcProxies();
        grant(OpenAccessValidation.KEY_TYPE_USER, KEY_ID.toString(), USER_DISPLAY_PREFIX);

        send(subject -> new RateLimitDecision(true, null, 0), request("/query/sync", "Bearer " + USER_KEY, PROXY, BDC_CHAIN));

        assertThat(emitted().getRequest().getSrcIp()).isEqualTo(CLIENT);
    }

    @Test
    void aDenialOnTheBdcChainRecordsTheResolvedClientAsSrcIp() throws Exception {
        bdcProxies();
        when(psama.validateOpenAccess(anyMap())).thenReturn(OpenAccessValidation.fromBoolean(false));

        MockHttpServletResponse response =
            send(subject -> new RateLimitDecision(true, null, 0), request("/query/sync", "Bearer " + USER_KEY, PROXY, BDC_CHAIN));

        assertThat(response.getStatus()).isEqualTo(401);
        LoggingEvent event = emitted();
        assertThat(event.getMetadata()).containsEntry("auth_action", "open_access.denied");
        assertThat(event.getRequest().getSrcIp()).isEqualTo(CLIENT);
    }

    @Test
    void aBreachOnTheBdcChainRecordsTheResolvedClientAsSrcIpAndSubject() throws Exception {
        bdcProxies();

        send(
            breaching(RateLimitSubject.Kind.PREVALIDATION_IP, new RateLimitDecision(false, Window.BURST, 12)),
            request("/query/sync", "Bearer " + USER_KEY, PROXY, BDC_CHAIN)
        );

        LoggingEvent event = emitted();
        assertThat(event.getRequest().getSrcIp()).isEqualTo(CLIENT);
        assertThat(event.getMetadata()).containsEntry("rate_limit_subject", CLIENT);
    }

    @Test
    void anUntrustedPeersForwardedForIsIgnoredForSrcIp() throws Exception {
        String peer = "198.51.100.9";
        grant(OpenAccessValidation.KEY_TYPE_USER, KEY_ID.toString(), USER_DISPLAY_PREFIX);

        send(subject -> new RateLimitDecision(true, null, 0), request("/query/sync", "Bearer " + USER_KEY, peer, CLIENT));

        assertThat(emitted().getRequest().getSrcIp()).isEqualTo(peer);
    }

    @Test
    void aCommaOnlyForwardedForFromATrustedPeerStillAuditsTheBreach() throws Exception {
        MockHttpServletResponse response = send(
            breaching(RateLimitSubject.Kind.PREVALIDATION_IP, new RateLimitDecision(false, Window.BURST, 12)),
            request("/query/sync", "Bearer " + USER_KEY, PROXY, ",")
        );

        assertThat(response.getStatus()).isEqualTo(429);
        LoggingEvent event = emitted();
        assertThat(event.getRequest().getStatus()).isEqualTo(429);
        assertThat(event.getRequest().getSrcIp()).isEqualTo(PROXY);
    }

    @Test
    void anOverCapChainBucketsOnTheProxyWithTheFailureFlaggedButKeepsTheAppendedHopAsSrcIp() throws Exception {
        // the caller's padding, then the hop httpd appended
        String overCap = String.join(", ", Collections.nCopies(ClientIpResolver.MAX_HOPS, "6.6.6.6")) + ", " + CLIENT;

        send(
            breaching(RateLimitSubject.Kind.PREVALIDATION_IP, new RateLimitDecision(false, Window.BURST, 12)),
            request("/query/sync", "Bearer " + USER_KEY, PROXY, overCap)
        );

        LoggingEvent event = emitted();
        // the caller's padding can move its bucket onto the proxy, but not its address out of the audit trail
        assertThat(event.getRequest().getSrcIp()).isEqualTo(CLIENT);
        assertThat(event.getMetadata()).containsEntry("rate_limit_subject", PROXY).containsEntry("rate_limit_ip_resolution_failed", true);
    }

    @Test
    void anOverCapChainSplitAcrossRepeatedHeadersStillKeepsTheAppendedHopAsSrcIp() throws Exception {
        MockHttpServletRequest request = request(
            "/query/sync", "Bearer " + USER_KEY, PROXY, String.join(", ", Collections.nCopies(ClientIpResolver.MAX_HOPS, "6.6.6.6"))
        );
        request.addHeader("X-Forwarded-For", CLIENT);

        send(breaching(RateLimitSubject.Kind.PREVALIDATION_IP, new RateLimitDecision(false, Window.BURST, 12)), request);

        LoggingEvent event = emitted();
        assertThat(event.getRequest().getSrcIp()).isEqualTo(CLIENT);
        assertThat(event.getMetadata()).containsEntry("rate_limit_ip_resolution_failed", true);
    }

    @Test
    void withNoTrustedProxiesSrcIpKeepsTheRightmostForwardedForEntry() throws Exception {
        trustedProxies = List.of();
        grant(OpenAccessValidation.KEY_TYPE_USER, KEY_ID.toString(), USER_DISPLAY_PREFIX);

        send(subject -> new RateLimitDecision(true, null, 0), request("/query/sync", "Bearer " + USER_KEY, PROXY, "6.6.6.6, " + CLIENT));

        assertThat(emitted().getRequest().getSrcIp()).isEqualTo(CLIENT);
    }

    @Test
    void aLoginTokenIsStillForwardedSoTheLoggingServiceCanReadItsClaims() throws Exception {
        String loginJwt = "Bearer eyJhbGciOiJIUzI1NiJ9.login.token";

        send(subject -> new RateLimitDecision(true, null, 0), request(loginJwt));

        assertThat(sent().authorization()).isEqualTo(loginJwt);
    }

    @Test
    void aPicsureTokenOutsideTheOpenAccessPrefixesIsStillForwarded() throws Exception {
        String loginToken = "Bearer picsure_l_AbCdEfGh0123456789";

        send(subject -> new RateLimitDecision(true, null, 0), request(loginToken));

        assertThat(sent().authorization()).isEqualTo(loginToken);
    }
}
