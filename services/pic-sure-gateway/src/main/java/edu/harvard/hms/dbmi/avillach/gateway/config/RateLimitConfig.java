package edu.harvard.hms.dbmi.avillach.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import edu.harvard.hms.dbmi.avillach.commons.net.ClientIpResolver;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.InMemoryRateLimitStore;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitProperties;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitStore;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Binds {@link RateLimitProperties} (validated at startup whether or not the limiter is enabled) and provides the bucket store and the
 * gateway's one {@link ClientIpResolver}. Both are always created; the store holds nothing until a stage filter consumes from it.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    RateLimitStore rateLimitStore(RateLimitProperties props, MeterRegistry meterRegistry) {
        return new InMemoryRateLimitStore(props, meterRegistry);
    }

    /** Trusts {@code X-Forwarded-For} only from the configured trusted proxies. */
    @Bean
    ClientIpResolver clientIpResolver(RateLimitProperties props) {
        return new ClientIpResolver(props.trustedProxyRanges());
    }
}
