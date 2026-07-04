package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Pins the resolved-effective-mode registration gate. All seven auth/audit filter beans register together whenever the resolved effective
 * mode is not TRANSPARENT, and none register when it is; the enforce-vs-observe difference is a per-request decision inside the filters,
 * not a registration difference. Cases: <ul> <li>{@link DefaultAuthDisabled}: nothing set → TRANSPARENT → zero filters.
 * <li>{@link AuthEnabled}: {@code auth-enabled=true}, mode unset → ENFORCE → all seven (today's production topology).
 * <li>{@link ModeObserveAuthDisabled}: {@code mode=observe} → OBSERVE → all seven (the full chain registers so gateway-owned routes can
 * enforce; the catch-all observe branch lives inside the filters). <li>{@link ModeEnforceAuthDisabled}: {@code mode=enforce},
 * {@code auth-enabled=false} → ENFORCE → all seven (regression for the prior bug where a bare {@code mode=enforce} registered only two
 * filters, silently dropping buffering/consent-mutation/identity/audit). </ul>
 */
class AuthEnabledGateTest {

    private static void assertAllSevenAbsent(ApplicationContext context) {
        assertThat(context.containsBean("bufferingFilter")).isFalse();
        assertThat(context.containsBean("openAccessFilter")).isFalse();
        assertThat(context.containsBean("introspectionFilter")).isFalse();
        assertThat(context.containsBean("bodyMutationFilter")).isFalse();
        assertThat(context.containsBean("tokenRefreshFilter")).isFalse();
        assertThat(context.containsBean("identityFilter")).isFalse();
        assertThat(context.containsBean("auditLoggingFilter")).isFalse();
    }

    private static void assertAllSevenPresent(ApplicationContext context) {
        assertThat(context.containsBean("bufferingFilter")).isTrue();
        assertThat(context.containsBean("openAccessFilter")).isTrue();
        assertThat(context.containsBean("introspectionFilter")).isTrue();
        assertThat(context.containsBean("bodyMutationFilter")).isTrue();
        assertThat(context.containsBean("tokenRefreshFilter")).isTrue();
        assertThat(context.containsBean("identityFilter")).isTrue();
        assertThat(context.containsBean("auditLoggingFilter")).isTrue();
    }

    @Nested
    @SpringBootTest
    class DefaultAuthDisabled {

        @Autowired
        private ApplicationContext context;

        @Test
        void authFilterBeansAreAbsentWhenModeUnsetAndAuthDisabled() {
            assertAllSevenAbsent(context);
        }

        @Test
        void inboundIdentityHeaderSanitizingFilterIsPresentEvenWithAuthDisabled() {
            // FIX 1: this filter is the dangerous-case defense -- it must register even when every gated auth-chain
            // filter above is absent, otherwise a client's spoofed X-User-* headers pass through untouched.
            assertThat(context.containsBean("inboundIdentityHeaderSanitizingFilter")).isTrue();
        }
    }

    @Nested
    @SpringBootTest
    @TestPropertySource(properties = "picsure.gateway.security.auth-enabled=true")
    class AuthEnabled {

        @Autowired
        private ApplicationContext context;

        @Test
        void authFilterBeansArePresentWhenAuthEnabledIsTrue() {
            assertAllSevenPresent(context);
        }

        @Test
        void inboundIdentityHeaderSanitizingFilterIsAlsoPresentWhenAuthEnabled() {
            assertThat(context.containsBean("inboundIdentityHeaderSanitizingFilter")).isTrue();
        }
    }

    /**
     * OBSERVE resolves to a non-TRANSPARENT effective mode, so the FULL chain registers -- gateway-owned routes must be able to enforce
     * during an observe window; the log-only catch-all behavior is a per-request branch inside the auth filters, NOT a missing filter.
     */
    @Nested
    @SpringBootTest
    @TestPropertySource(properties = "picsure.gateway.security.mode=observe")
    class ModeObserveAuthDisabled {

        @Autowired
        private ApplicationContext context;

        @Test
        void allSevenFiltersRegisterInObserveMode() {
            assertAllSevenPresent(context);
        }

        @Test
        void inboundIdentityHeaderSanitizingFilterIsStillPresent() {
            assertThat(context.containsBean("inboundIdentityHeaderSanitizingFilter")).isTrue();
        }
    }

    /**
     * Regression: a bare {@code mode=enforce} with {@code auth-enabled=false} resolves to ENFORCE and must register ALL seven filters --
     * previously it registered only two (open-access + introspection), silently dropping buffering (PSAMA never saw the POST body),
     * body-mutation (consent-constrained query never applied downstream = authorization bypass), identity propagation, and audit.
     */
    @Nested
    @SpringBootTest
    @TestPropertySource(properties = {"picsure.gateway.security.mode=enforce", "picsure.gateway.security.auth-enabled=false"})
    class ModeEnforceAuthDisabled {

        @Autowired
        private ApplicationContext context;

        @Test
        void allSevenFiltersRegisterWhenModeEnforceEvenWithAuthDisabled() {
            assertAllSevenPresent(context);
        }
    }
}
