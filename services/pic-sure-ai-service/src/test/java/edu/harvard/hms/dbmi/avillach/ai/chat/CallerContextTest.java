package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

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
    void fromReadsTheGatewayAccessType() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer abc");
        request.addHeader("X-Picsure-Access-Type", "open");

        assertThat(CallerContext.from(request).accessType()).isEqualTo("open");
    }

    /** An unsigned {@code picsure_s_} token with this payload; the signature is the gateway's to check, not this service's. */
    private static String sessionToken(String payloadJson) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return "Bearer picsure_s_eyJhbGciOiJIUzI1NiJ9." + payload + ".sig";
    }

    private static final String SESSION = "8694e3d4-5cb4-410f-8431-993445e6d3f6";

    @Test
    void aLoggedInUserKeepsTheSameOwnerWhenTheirTokenIsReplaced() {
        CallerContext before = new CallerContext("Bearer jwt-1", null, "user-1", "authorized");
        CallerContext after = new CallerContext("Bearer jwt-2", null, "user-1", "authorized");

        assertThat(before.owner()).isEqualTo("user:user-1").isEqualTo(after.owner());
        assertThat(new CallerContext("Bearer jwt-1", null, "user-2", "authorized").owner()).isNotEqualTo(before.owner());
    }

    @Test
    void aTokenCallerKeepsTheSameOwnerWhateverCaseTheSchemeIsIn() {
        assertThat(new CallerContext("bearer key-1", null, null).owner()).isEqualTo(new CallerContext("Bearer key-1", null, null).owner());
        assertThat(new CallerContext("Bearer Key-1", null, null).owner()).isNotEqualTo(new CallerContext("Bearer key-1", null, null).owner());
    }

    @Test
    void anOpenAccessSessionKeepsTheSameOwnerWhenItsTokenIsRefreshed() {
        String first = sessionToken("{\"sub\":\"" + SESSION + "\",\"iat\":1}");
        String refreshed = sessionToken("{\"sub\":\"" + SESSION + "\",\"iat\":500}");
        String otherSession = sessionToken("{\"sub\":\"1b4e28ba-2fa1-11d2-883f-0016d3cca427\",\"iat\":1}");

        // Every open-access caller has the same X-User-Id, so it must not be what tells them apart.
        CallerContext caller = new CallerContext(first, null, "OPEN_ACCESS:localhost", "open");

        assertThat(caller.owner()).isEqualTo("session:" + SESSION)
            .isEqualTo(new CallerContext(refreshed, null, "OPEN_ACCESS:localhost", "open").owner());
        assertThat(new CallerContext(otherSession, null, "OPEN_ACCESS:localhost", "open").owner()).isNotEqualTo(caller.owner());
    }

    @Test
    void aSessionIdIsTrustedOnlyWhenTheGatewayGrantedOpenAccess() {
        String token = sessionToken("{\"sub\":\"" + SESSION + "\"}");

        assertThat(new CallerContext(token, null, null, null).owner()).startsWith("token:");
        assertThat(new CallerContext(token, null, "user-1", "authorized").owner()).isEqualTo("user:user-1");
    }

    @Test
    void anyOtherCallerIsKeyedByAHashOfTheirTokenNeverTheTokenItself() {
        String apiKey = "Bearer picsure_u_00000000000000000000000000000000000000000003tr27S";
        String notASession = sessionToken("{\"sub\":\"not-a-uuid\"}");

        assertThat(new CallerContext(apiKey, null, "OPEN_ACCESS:localhost", "open").owner()).startsWith("token:").doesNotContain("picsure_u_");
        assertThat(new CallerContext(notASession, null, "OPEN_ACCESS:localhost", "open").owner()).startsWith("token:");
        assertThat(new CallerContext("Bearer picsure_s_garbage", null, null, "open").owner()).startsWith("token:");
        assertThat(new CallerContext("Bearer abc", null, null).owner()).isNotEqualTo(new CallerContext("Bearer abd", null, null).owner());
    }

    @Test
    void toStringNeverPrintsTheToken() {
        assertThat(new CallerContext("Bearer secret", "r", "u").toString()).contains("<redacted>", "r", "u").doesNotContain("secret");
        assertThat(new CallerContext(null, "r", "u").toString()).contains("<absent>");
        assertThat(new CallerContext(" ", "r", "u").toString()).contains("<absent>");
    }
}
