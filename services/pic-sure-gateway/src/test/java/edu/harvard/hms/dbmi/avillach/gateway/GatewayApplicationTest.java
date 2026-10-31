package edu.harvard.hms.dbmi.avillach.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;

import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitStore;

@SpringBootTest
class GatewayApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    /**
     * Bucket4j on the classpath activates Spring Cloud Gateway's own {@code Bucket4jFilterConfiguration}. No route uses its
     * {@code RateLimit} filter (the limiter is our own filters over {@link RateLimitStore}), but the context must still start with it.
     */
    @Test
    void startsWithBucket4jOnTheClasspath() {
        assertThat(context.containsBean("bucket4jFilterFunctionsSupplier")).isTrue();
        assertThat(context.getBeanNamesForType(RateLimitStore.class)).hasSize(1);
    }

    /**
     * Guards the {@code UserDetailsServiceAutoConfiguration} exclusion on the application class. Without it Spring Boot registers an
     * {@code InMemoryUserDetailsManager} holding a {@code user} account with a random password and logs that password at startup, even
     * though no filter chain here ever authenticates against it.
     */
    @Test
    void noGeneratedInMemoryUserIsCreated() {
        assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
    }
}
