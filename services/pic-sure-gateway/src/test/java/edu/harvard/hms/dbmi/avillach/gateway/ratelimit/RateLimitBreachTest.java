package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitDecision.Window;

class RateLimitBreachTest {

    private static final RateLimitSubject USER = RateLimitSubject.user(UUID.fromString("00000000-0000-0000-0000-000000000001"));

    @Test
    void rejectKeepsHeadersSetEarlierAndRecordsTheBreach() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("X-PICSURE-Session-Refresh", "picsure_s_refreshed");
        RateLimitDecision decision = new RateLimitDecision(false, Window.SUSTAINED, 3600);

        RateLimitBreach.reject(request, response, USER, decision);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3600");
        assertThat(response.getHeader("X-PICSURE-Session-Refresh")).isEqualTo("picsure_s_refreshed");
        assertThat(response.getContentAsString()).contains("\"errorType\":\"rate_limited\"").contains("3600 seconds.");
        assertThat(request.getAttribute(RateLimitBreach.REQUEST_ATTRIBUTE)).isEqualTo(new RateLimitBreach(USER, decision));
    }

    @Test
    void aOneSecondWaitReadsInTheSingular() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        RateLimitBreach.reject(new MockHttpServletRequest(), response, USER, new RateLimitDecision(false, Window.BURST, 1));

        assertThat(response.getContentAsString()).contains("Try again in 1 second.");
    }

    @Test
    void anAllowedDecisionIsNotABreach() {
        assertThatThrownBy(() -> new RateLimitBreach(USER, new RateLimitDecision(true, null, 0)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
