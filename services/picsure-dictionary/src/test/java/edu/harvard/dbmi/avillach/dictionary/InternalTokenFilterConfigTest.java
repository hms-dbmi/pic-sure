package edu.harvard.dbmi.avillach.dictionary;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import edu.harvard.hms.dbmi.avillach.commons.security.InternalTokenFilter;

/**
 * Verifies that {@link InternalTokenFilterConfig} scopes the token gate to {@code /internal/*} and compares against the configured token
 * with surrounding whitespace removed, the way HPDS removes it before sending, while a token that is blank after stripping still rejects
 * every request.
 */
class InternalTokenFilterConfigTest {

    private static final String URI = "/internal/concepts/export-metadata";

    private static int statusFor(String configured, String provided) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", URI);
        if (provided != null) {
            request.addHeader(InternalTokenFilter.HEADER, provided);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        new InternalTokenFilterConfig().internalTokenFilter(configured).getFilter().doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void registersTheFilterScopedToInternalUrlPatternOnly() {
        FilterRegistrationBean<InternalTokenFilter> registration = new InternalTokenFilterConfig().internalTokenFilter("secret");

        assertThat(registration.getUrlPatterns()).containsExactly("/internal/*");
        assertThat(registration.getFilter()).isInstanceOf(InternalTokenFilter.class);
    }

    @ParameterizedTest(name = "configured token [{0}] accepts the stripped value")
    @ValueSource(strings = {"shared-secret", "shared-secret\r", "shared-secret\r\n", "shared-secret ", " \tshared-secret \n"})
    void acceptsTheConfiguredTokenWithSurroundingWhitespaceRemoved(String configured) throws Exception {
        assertThat(statusFor(configured, "shared-secret")).isEqualTo(200);
        assertThat(statusFor(configured, "other-secret")).isEqualTo(403);
        assertThat(statusFor(configured, null)).isEqualTo(403);
    }

    @ParameterizedTest(name = "configured token [{0}] rejects everything")
    @NullSource
    @ValueSource(strings = {"", " ", "\r\n", " \t "})
    void aTokenThatIsBlankAfterStrippingRejectsEveryRequest(String configured) throws Exception {
        assertThat(statusFor(configured, null)).isEqualTo(403);
        assertThat(statusFor(configured, "")).isEqualTo(403);
        assertThat(statusFor(configured, " ")).isEqualTo(403);
        assertThat(statusFor(configured, "anything")).isEqualTo(403);
        if (configured != null) {
            assertThat(statusFor(configured, configured)).isEqualTo(403);
        }
    }
}
