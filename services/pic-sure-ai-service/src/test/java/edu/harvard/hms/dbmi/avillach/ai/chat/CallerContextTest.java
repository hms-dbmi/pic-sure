package edu.harvard.hms.dbmi.avillach.ai.chat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class CallerContextTest {

    @Test
    void fromReadsAuthorizationRequestIdAndGatewayUserId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer abc");
        request.addHeader("X-Request-Id", "req-1");
        request.addHeader("X-User-Id", "user-1");

        CallerContext caller = CallerContext.from(request);

        assertThat(caller.authorization()).isEqualTo("Bearer abc");
        assertThat(caller.requestId()).isEqualTo("req-1");
        assertThat(caller.userId()).isEqualTo("user-1");
    }

    @Test
    void fromLeavesUserIdNullWhenTheGatewayHeaderIsMissing() {
        CallerContext caller = CallerContext.from(new MockHttpServletRequest());

        assertThat(caller.authorization()).isNull();
        assertThat(caller.userId()).isNull();
    }

    @Test
    void applyToSetsTheAuthorizationHeaderOnlyWhenPresent() {
        HttpHeaders headers = new HttpHeaders();
        new CallerContext("Bearer abc", null, null).applyTo(headers);
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer abc");

        HttpHeaders blank = new HttpHeaders();
        new CallerContext("  ", null, null).applyTo(blank);
        new CallerContext(null, null, null).applyTo(blank);
        assertThat(blank).isEmpty();
    }

    @Test
    void toStringNeverPrintsTheToken() {
        assertThat(new CallerContext("Bearer secret", "r", "u").toString()).contains("<redacted>", "r", "u").doesNotContain("secret");
        assertThat(new CallerContext(null, "r", "u").toString()).contains("<absent>");
        assertThat(new CallerContext(" ", "r", "u").toString()).contains("<absent>");
    }
}
