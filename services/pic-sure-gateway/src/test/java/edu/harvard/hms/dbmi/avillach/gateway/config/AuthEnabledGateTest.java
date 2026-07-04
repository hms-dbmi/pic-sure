package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Pins the master auth switch (Task 14): with {@code picsure.gateway.security.auth-enabled} unset/false (the safe-by-default value), NONE
 * of the auth/audit filter registration beans should exist in the context -- the gateway must be a pure pass-through even with the auth
 * code deployed. Flipping the switch to {@code true} must register all seven filter beans (six from {@link SecurityConfig}, one audit
 * filter from {@code AuditFilterConfig}). {@link ModeObserveAuthDisabled} additionally pins the parity-verification precedence: OBSERVE
 * mode registers only the two filters capable of an observe branch, without auth-enabled's other five.
 */
class AuthEnabledGateTest {

    @Nested
    @SpringBootTest
    class DefaultAuthDisabled {

        @Autowired
        private ApplicationContext context;

        @Test
        void authFilterBeansAreAbsentWhenAuthEnabledDefaultsFalse() {
            assertThat(context.containsBean("bufferingFilter")).isFalse();
            assertThat(context.containsBean("openAccessFilter")).isFalse();
            assertThat(context.containsBean("introspectionFilter")).isFalse();
            assertThat(context.containsBean("bodyMutationFilter")).isFalse();
            assertThat(context.containsBean("tokenRefreshFilter")).isFalse();
            assertThat(context.containsBean("identityFilter")).isFalse();
            assertThat(context.containsBean("auditLoggingFilter")).isFalse();
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
            assertThat(context.containsBean("bufferingFilter")).isTrue();
            assertThat(context.containsBean("openAccessFilter")).isTrue();
            assertThat(context.containsBean("introspectionFilter")).isTrue();
            assertThat(context.containsBean("bodyMutationFilter")).isTrue();
            assertThat(context.containsBean("tokenRefreshFilter")).isTrue();
            assertThat(context.containsBean("identityFilter")).isTrue();
            assertThat(context.containsBean("auditLoggingFilter")).isTrue();
        }

        @Test
        void inboundIdentityHeaderSanitizingFilterIsAlsoPresentWhenAuthEnabled() {
            assertThat(context.containsBean("inboundIdentityHeaderSanitizingFilter")).isTrue();
        }
    }

    /**
     * Parity-verification precedence (gateway-parity-verification plan, Tasks 4-6): {@code picsure.gateway.security.mode=observe} with
     * {@code auth-enabled} still unset/false must register ONLY {@code openAccessFilter} and {@code introspectionFilter} (so their OBSERVE
     * branches can build + shadow-log + forward) via {@link GatewayAuthActiveCondition} -- {@code bufferingFilter},
     * {@code bodyMutationFilter}, {@code tokenRefreshFilter}, {@code identityFilter}, and {@code auditLoggingFilter} stay absent, exactly
     * as in the default-disabled case, so OBSERVE never buffers/caps, mutates, or propagates identity headers.
     */
    @Nested
    @SpringBootTest
    @TestPropertySource(properties = "picsure.gateway.security.mode=observe")
    class ModeObserveAuthDisabled {

        @Autowired
        private ApplicationContext context;

        @Test
        void onlyTheTwoObserveCapableFiltersAreRegistered() {
            assertThat(context.containsBean("openAccessFilter")).isTrue();
            assertThat(context.containsBean("introspectionFilter")).isTrue();

            assertThat(context.containsBean("bufferingFilter")).isFalse();
            assertThat(context.containsBean("bodyMutationFilter")).isFalse();
            assertThat(context.containsBean("tokenRefreshFilter")).isFalse();
            assertThat(context.containsBean("identityFilter")).isFalse();
            assertThat(context.containsBean("auditLoggingFilter")).isFalse();
        }

        @Test
        void inboundIdentityHeaderSanitizingFilterIsStillPresent() {
            assertThat(context.containsBean("inboundIdentityHeaderSanitizingFilter")).isTrue();
        }
    }
}
