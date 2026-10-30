package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import edu.harvard.hms.dbmi.avillach.commons.net.ClientIpResolver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** The gateway's one {@link ClientIpResolver} is where configuration becomes trust: it must honour exactly the trusted-proxy list. */
class RateLimitConfigTest {

    private static final String TRUSTED_PROXY = "10.89.0.5";
    private static final String ALLOWLISTED_SOURCE = "192.0.2.9";
    private static final String CLIENT = "203.0.113.7";

    private final ApplicationContextRunner runner =
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(RateLimitConfig.class).withBean(MeterRegistry.class, SimpleMeterRegistry::new).withPropertyValues(
                "RATE_LIMIT_ENABLED=true", "RATE_LIMIT_TRUSTED_PROXIES=10.89.0.0/24",
                "RATE_LIMIT_PLATFORM_ALLOWLISTED_SOURCE_CIDRS=192.0.2.0/24"
            );

    @Test
    void resolverHonoursForwardedForFromATrustedProxy() {
        runner.run(context -> {
            ClientIpResolver resolver = context.getBean(ClientIpResolver.class);

            assertThat(resolver.resolve(TRUSTED_PROXY, CLIENT).address().getHostAddress()).isEqualTo(CLIENT);
        });
    }

    @Test
    void resolverIgnoresForwardedForFromAnAllowlistedSourceThatIsNotATrustedProxy() {
        runner.run(context -> {
            ClientIpResolver resolver = context.getBean(ClientIpResolver.class);

            assertThat(resolver.resolve(ALLOWLISTED_SOURCE, CLIENT).address().getHostAddress()).isEqualTo(ALLOWLISTED_SOURCE);
        });
    }
}
