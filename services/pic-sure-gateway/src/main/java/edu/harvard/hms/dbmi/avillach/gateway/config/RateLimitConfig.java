package edu.harvard.hms.dbmi.avillach.gateway.config;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationPropertiesBindHandlerAdvisor;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.AbstractBindHandler;
import org.springframework.boot.context.properties.bind.BindContext;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;

import edu.harvard.hms.dbmi.avillach.commons.net.CidrRange;
import edu.harvard.hms.dbmi.avillach.commons.net.ClientIpResolver;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.InMemoryRateLimitStore;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitProperties;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitStore;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Binds {@link RateLimitProperties} (validated at startup whether or not the limiter is enabled), with its CIDR lists parsed to
 * {@link CidrRange}, and provides the bucket store and the gateway's one {@link ClientIpResolver}. Both are always created; the store holds
 * nothing until a stage filter consumes from it.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    /** The env var {@code application.yml} reads each CIDR list from, so a bad entry's startup failure can name it. */
    private static final Map<String, String> CIDR_LIST_ENV_VARS = Map.of(
        "picsure.gateway.rate-limit.trusted-proxies", "RATE_LIMIT_TRUSTED_PROXIES",
        "picsure.gateway.rate-limit.platform-allowlisted-source-cidrs", "RATE_LIMIT_PLATFORM_ALLOWLISTED_SOURCE_CIDRS"
    );

    /**
     * Binds each entry of a comma-separated CIDR list to a {@link CidrRange}. A blank entry becomes null, which {@link RateLimitProperties}
     * drops. Static so the binder can use it before this configuration is instantiated.
     */
    @Bean
    @ConfigurationPropertiesBinding
    static Converter<String, CidrRange> cidrRangeConverter() {
        // An anonymous class rather than a lambda: the conversion service reads the source and target types from its generic signature.
        return new Converter<String, CidrRange>() {
            @Override
            public CidrRange convert(String source) {
                return source.isBlank() ? null : CidrRange.parse(source);
            }
        };
    }

    /**
     * Restates a CIDR list's binding failure as {@code <property> (<env var>): Invalid CIDR '<entry>': <reason>}. The binder's own failure
     * names the property but not the env var deployments set, and Boot's startup report shows only the innermost reason, which drops the
     * entry. So the restated message carries no cause.
     */
    @Bean
    static ConfigurationPropertiesBindHandlerAdvisor cidrListFailureMessages() {
        return handler -> new AbstractBindHandler(handler) {
            @Override
            public Object onFailure(ConfigurationPropertyName name, Bindable<?> target, BindContext context, Exception error)
                throws Exception {
                String envVar = CIDR_LIST_ENV_VARS.get(name.toString());
                Throwable invalid = error;
                while (invalid != null && !(invalid instanceof IllegalArgumentException)) {
                    invalid = invalid.getCause();
                }
                if (envVar == null || invalid == null) {
                    return super.onFailure(name, target, context, error);
                }
                throw new IllegalArgumentException(name + " (" + envVar + "): " + invalid.getMessage());
            }
        };
    }

    @Bean
    RateLimitStore rateLimitStore(RateLimitProperties props, MeterRegistry meterRegistry) {
        return new InMemoryRateLimitStore(props, meterRegistry);
    }

    /** Trusts {@code X-Forwarded-For} only from the configured trusted proxies. */
    @Bean
    ClientIpResolver clientIpResolver(RateLimitProperties props) {
        return new ClientIpResolver(props.trustedProxies());
    }
}
