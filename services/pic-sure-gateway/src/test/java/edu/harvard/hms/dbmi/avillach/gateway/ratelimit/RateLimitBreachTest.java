package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.net.ClientIp;
import edu.harvard.hms.dbmi.avillach.gateway.auth.OpenAccessValidation;
import edu.harvard.hms.dbmi.avillach.gateway.filter.OpenAccessFilter;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitDecision.Window;

class RateLimitBreachTest {

    private static final UUID KEY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final RateLimitSubject USER = RateLimitSubject.user(KEY_ID);

    private final AuditContext audit = new AuditContext();

    private static MockHttpServletRequest granted(String keyType, String keyId, String displayPrefix) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        request.setAttribute(
            OpenAccessFilter.ATTR_OPEN_ACCESS_VALIDATION, new OpenAccessValidation(true, keyType, keyId, displayPrefix, null, null)
        );
        return request;
    }

    @Test
    void rejectKeepsHeadersSetEarlierAndRecordsTheBreach() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("X-PICSURE-Session-Refresh", "picsure_s_refreshed");
        RateLimitDecision decision = new RateLimitDecision(false, Window.SUSTAINED, 3600);

        RateLimitBreach.reject(request, response, audit, USER, null, decision);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3600");
        assertThat(response.getHeader("X-PICSURE-Session-Refresh")).isEqualTo("picsure_s_refreshed");
        assertThat(response.getContentAsString()).contains("\"errorType\":\"rate_limited\"").contains("3600 seconds.");
        assertThat(request.getAttribute(RateLimitBreach.REQUEST_ATTRIBUTE)).isEqualTo(new RateLimitBreach(USER, decision));
    }

    @Test
    void aOneSecondWaitReadsInTheSingular() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        RateLimitBreach.reject(new MockHttpServletRequest(), response, audit, USER, null, new RateLimitDecision(false, Window.BURST, 1));

        assertThat(response.getContentAsString()).contains("Try again in 1 second.");
    }

    @Test
    void anAllowedDecisionIsNotABreach() {
        assertThatThrownBy(() -> new RateLimitBreach(USER, new RateLimitDecision(true, null, 0)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aUserKeyBreachReplacesTheGrantWithTheKeyUuidAndDisplayPrefix() throws Exception {
        audit.put("auth_result", "success");
        audit.put("auth_action", "open_access.granted");
        MockHttpServletRequest request = granted(OpenAccessValidation.KEY_TYPE_USER, KEY_ID.toString(), "picsure_u_AbCd");

        RateLimitBreach.reject(request, new MockHttpServletResponse(), audit, USER, null, new RateLimitDecision(false, Window.BURST, 30));

        assertThat(audit.getMetadata()).containsEntry("auth_result", "failure").containsEntry("auth_action", "open_access.rate_limited")
            .containsEntry("auth_failure_reason", "rate_limited").containsEntry("rate_limit_subject_type", "user_key")
            .containsEntry("rate_limit_subject", KEY_ID.toString()).containsEntry("rate_limit_key_display_prefix", "picsure_u_AbCd")
            .containsEntry("rate_limit_window", "burst").containsEntry("rate_limit_retry_after_seconds", 30L);
    }

    @Test
    void aSessionBreachCarriesItsSubAndTheSustainedWindow() throws Exception {
        MockHttpServletRequest request = granted(OpenAccessValidation.KEY_TYPE_SESSION, "sub-one", null);

        RateLimitBreach.reject(
            request, new MockHttpServletResponse(), audit, RateLimitSubject.session("sub-one"), null,
            new RateLimitDecision(false, Window.SUSTAINED, 50_000)
        );

        assertThat(audit.getMetadata()).containsEntry("rate_limit_subject_type", "session").containsEntry("rate_limit_subject", "sub-one")
            .containsEntry("rate_limit_window", "sustained").containsEntry("rate_limit_retry_after_seconds", 50_000L)
            .doesNotContainKey("rate_limit_key_display_prefix");
    }

    @Test
    void aBreachOfBothWindowsIsReportedAsTheSustainedOneItsRetryAfterWaitsOut() throws Exception {
        RateLimitBreach.reject(
            new MockHttpServletRequest(), new MockHttpServletResponse(), audit, USER, null,
            new RateLimitDecision(false, Window.BOTH, 50_000)
        );

        assertThat(audit.getMetadata()).containsEntry("rate_limit_window", "sustained");
    }

    @Test
    void anIpBucketedBreachLogsTheFullAddressAndNoKeyFieldsEvenForAVerifiedKey() throws Exception {
        InetAddress client = InetAddress.getByName("2001:db8:1:2:3:4:5:6");
        // a PLATFORM key from a non-allowlisted source is IP-bucketed
        MockHttpServletRequest request = granted(OpenAccessValidation.KEY_TYPE_PLATFORM, KEY_ID.toString(), "picsure_p_AbCd");

        RateLimitBreach.reject(
            request, new MockHttpServletResponse(), audit, RateLimitSubject.ip(client), new ClientIp(client, false),
            new RateLimitDecision(false, Window.BURST, 30)
        );

        assertThat(audit.getMetadata()).containsEntry("rate_limit_subject_type", "client_ip")
            .containsEntry("rate_limit_subject", client.getHostAddress()).containsEntry("rate_limit_window", "burst")
            .doesNotContainKey("rate_limit_key_display_prefix").doesNotContainKey("rate_limit_ip_resolution_failed");
        assertThat(audit.getMetadata().values()).doesNotContain(KEY_ID.toString(), "picsure_p_AbCd");
    }

    @Test
    void aPrevalidationBreachIsTheClientIpInThePrevalidationWindow() throws Exception {
        InetAddress client = InetAddress.getByName("203.0.113.7");

        RateLimitBreach.reject(
            new MockHttpServletRequest(), new MockHttpServletResponse(), audit, RateLimitSubject.prevalidationIp(client),
            new ClientIp(client, false), new RateLimitDecision(false, Window.BURST, 12)
        );

        assertThat(audit.getMetadata()).containsEntry("rate_limit_subject_type", "client_ip")
            .containsEntry("rate_limit_subject", "203.0.113.7").containsEntry("rate_limit_window", "prevalidation")
            .containsEntry("rate_limit_retry_after_seconds", 12L).doesNotContainKey("rate_limit_ip_resolution_failed");
    }

    @Test
    void anIpBreachAfterAFailedResolutionSaysTheSubjectIsOnlyThePeer() throws Exception {
        InetAddress peer = InetAddress.getByName("10.0.0.5");

        RateLimitBreach.reject(
            new MockHttpServletRequest(), new MockHttpServletResponse(), audit, RateLimitSubject.prevalidationIp(peer),
            new ClientIp(peer, true), new RateLimitDecision(false, Window.BURST, 12)
        );

        assertThat(audit.getMetadata()).containsEntry("rate_limit_subject", "10.0.0.5")
            .containsEntry("rate_limit_ip_resolution_failed", true);
    }
}
