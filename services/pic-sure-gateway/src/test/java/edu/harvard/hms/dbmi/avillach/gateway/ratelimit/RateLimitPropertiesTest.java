package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import edu.harvard.hms.dbmi.avillach.commons.net.CidrRange;
import edu.harvard.hms.dbmi.avillach.gateway.config.RateLimitConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Binds {@code picsure.gateway.rate-limit.*} from the real {@code application.yml}, so the defaults and env var names are pinned here. */
class RateLimitPropertiesTest {

    private final ApplicationContextRunner runner =
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(RateLimitConfig.class).withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void defaultsBindWithNoEnvSet() {
        runner.run(context -> {
            RateLimitProperties props = context.getBean(RateLimitProperties.class);
            assertThat(props.enabled()).isFalse();
            assertThat(props.user()).isEqualTo(new RateLimitProperties.KeyLimits(60, 3000));
            assertThat(props.session()).isEqualTo(new RateLimitProperties.KeyLimits(60, 3000));
            assertThat(props.ip()).isEqualTo(new RateLimitProperties.IpLimits(60, 3000, 300));
            assertThat(props.store()).isEqualTo(new RateLimitProperties.Store(100_000));
            assertThat(props.trustedProxies()).isEmpty();
            assertThat(props.platformAllowlistedSourceCidrs()).isEmpty();
            assertThat(context).hasSingleBean(RateLimitStore.class);
        });
    }

    @ParameterizedTest
    @CsvSource(
        {"RATE_LIMIT_USER_BURST_PER_MINUTE, user.burstPerMinute", "RATE_LIMIT_USER_SUSTAINED_PER_DAY, user.sustainedPerDay",
            "RATE_LIMIT_SESSION_BURST_PER_MINUTE, session.burstPerMinute", "RATE_LIMIT_SESSION_SUSTAINED_PER_DAY, session.sustainedPerDay",
            "RATE_LIMIT_IP_BURST_PER_MINUTE, ip.burstPerMinute", "RATE_LIMIT_IP_SUSTAINED_PER_DAY, ip.sustainedPerDay",
            "RATE_LIMIT_IP_PREVALIDATION_PER_MINUTE, ip.prevalidationPerMinute", "RATE_LIMIT_STORE_MAX_SUBJECTS, store.maxSubjects"}
    )
    void eachLimitBindsFromItsEnvVar(String envVar, String field) {
        runner.withPropertyValues(envVar + "=7").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RateLimitProperties.class)).extracting(field).isEqualTo(7L);
        });
    }

    @Test
    void switchAndListsBindFromTheirEnvVars() {
        runner.withPropertyValues(
            "RATE_LIMIT_ENABLED=true", "RATE_LIMIT_TRUSTED_PROXIES=10.0.1.0/24, 192.0.2.10 , 2001:db8::1",
            "RATE_LIMIT_PLATFORM_ALLOWLISTED_SOURCE_CIDRS=192.0.2.0/24"
        ).run(context -> {
            RateLimitProperties props = context.getBean(RateLimitProperties.class);
            assertThat(props.enabled()).isTrue();
            assertThat(props.trustedProxies())
                .containsExactly(CidrRange.parse("10.0.1.0/24"), CidrRange.parse("192.0.2.10/32"), CidrRange.parse("2001:db8::1/128"));
            assertThat(props.trustedProxies()).map(CidrRange::toString).containsExactly("10.0.1.0/24", "192.0.2.10", "2001:db8::1");
            assertThat(props.platformAllowlistedSourceCidrs()).containsExactly(CidrRange.parse("192.0.2.0/24"));
        });
    }

    @ParameterizedTest
    @CsvSource(
        {"RATE_LIMIT_TRUSTED_PROXIES, trustedProxies", "RATE_LIMIT_PLATFORM_ALLOWLISTED_SOURCE_CIDRS, platformAllowlistedSourceCidrs"}
    )
    void blankListEntriesAreSkipped(String envVar, String field) {
        runner.withPropertyValues(envVar + "=10.0.0.0/8,,192.0.2.10, ,").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RateLimitProperties.class)).extracting(field)
                .isEqualTo(List.of(CidrRange.parse("10.0.0.0/8"), CidrRange.parse("192.0.2.10")));
        });
    }

    @ParameterizedTest
    @CsvSource(
        {"RATE_LIMIT_TRUSTED_PROXIES, trustedProxies", "RATE_LIMIT_PLATFORM_ALLOWLISTED_SOURCE_CIDRS, platformAllowlistedSourceCidrs"}
    )
    void emptyListValueBindsToNoRanges(String envVar, String field) {
        runner.withPropertyValues(envVar + "=").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RateLimitProperties.class)).extracting(field).isEqualTo(List.of());
        });
    }

    @ParameterizedTest
    @CsvSource(
        {"user.burst-per-minute, 0", "user.sustained-per-day, -1", "session.burst-per-minute, 0", "session.sustained-per-day, 0",
            "ip.burst-per-minute, 0", "ip.sustained-per-day, 0", "ip.prevalidation-per-minute, -5", "store.max-subjects, 0"}
    )
    void nonPositiveLimitStopsStartupNamingTheProperty(String key, String value) {
        runner.withPropertyValues("picsure.gateway.rate-limit." + key + "=" + value).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                .hasMessage("picsure.gateway.rate-limit." + key + " must be a positive integer, got " + value);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " , "})
    void enabledWithNoTrustedProxiesStopsStartupNamingHttpd(String trustedProxies) {
        runner.withPropertyValues("RATE_LIMIT_ENABLED=true", "RATE_LIMIT_TRUSTED_PROXIES=" + trustedProxies).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining(
                "picsure.gateway.rate-limit.enabled is true but picsure.gateway.rate-limit.trusted-proxies (RATE_LIMIT_TRUSTED_PROXIES) is "
                    + "empty. Add the reverse proxy in front of the gateway (httpd): without it every request appears to come from that "
                    + "proxy and all clients share one rate-limit bucket."
            );
        });
    }

    @ParameterizedTest
    @CsvSource(
        {"RATE_LIMIT_TRUSTED_PROXIES, trusted-proxies", "RATE_LIMIT_PLATFORM_ALLOWLISTED_SOURCE_CIDRS, platform-allowlisted-source-cidrs"}
    )
    void malformedCidrStopsStartupNamingThePropertyEnvVarAndEntry(String envVar, String property) {
        runner.withPropertyValues(envVar + "=10.0.0.0/8, 10.0.0.0/33").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessage(
                "picsure.gateway.rate-limit." + property + " (" + envVar + "): Invalid CIDR '10.0.0.0/33': prefix length over 32"
            );
        });
    }

    @Test
    void enabledWithTrustedProxiesAndEmptyAllowlistStarts() {
        runner.withPropertyValues("RATE_LIMIT_ENABLED=true", "RATE_LIMIT_TRUSTED_PROXIES=10.89.0.0/24").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(RateLimitProperties.class).platformAllowlistedSourceCidrs()).isEmpty();
        });
    }
}
