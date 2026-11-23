package edu.harvard.hms.dbmi.avillach.operations.config;

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
 * Verifies that the {@link InternalTokenFilterConfig} bean scopes registration to the container-level {@code /internal/*} URL pattern
 * rather than the default {@code /*}, and that it compares against the configured secret with surrounding whitespace removed while a secret
 * that is blank after stripping still rejects every request.
 */
class InternalTokenFilterConfigTest {

    @Test
    void registersTheFilterScopedToInternalUrlPatternOnly() {
        InternalTokenFilterConfig config = new InternalTokenFilterConfig();

        FilterRegistrationBean<InternalTokenFilter> registration = config.internalTokenFilter("secret");

        assertThat(registration.getUrlPatterns()).containsExactly("/internal/*");
        assertThat(registration.getFilter()).isInstanceOf(InternalTokenFilter.class);
    }

    @Test
    void acceptsTheConfiguredSecretWithSurroundingWhitespaceRemoved() throws Exception {
        assertThat(statusFor(" \tshared-secret\r\n", "shared-secret")).isEqualTo(200);
        assertThat(statusFor(" \tshared-secret\r\n", "other-secret")).isEqualTo(403);
        assertThat(statusFor(" \tshared-secret\r\n", null)).isEqualTo(403);
    }

    @ParameterizedTest(name = "configured secret [{0}] rejects everything")
    @NullSource
    @ValueSource(strings = {"", " ", "\r\n"})
    void aSecretThatIsBlankAfterStrippingRejectsEveryRequest(String configured) throws Exception {
        assertThat(statusFor(configured, null)).isEqualTo(403);
        assertThat(statusFor(configured, "")).isEqualTo(403);
        assertThat(statusFor(configured, "anything")).isEqualTo(403);
    }

    private static int statusFor(String configured, String provided) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/queries/abc/dispatch");
        if (provided != null) {
            request.addHeader(InternalTokenFilter.HEADER, provided);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        new InternalTokenFilterConfig().internalTokenFilter(configured).getFilter().doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
