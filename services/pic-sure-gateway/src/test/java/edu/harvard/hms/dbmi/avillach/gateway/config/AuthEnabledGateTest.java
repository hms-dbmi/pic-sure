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
 * filter from {@code AuditFilterConfig}).
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
    }
}
