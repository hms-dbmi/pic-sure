package edu.harvard.hms.dbmi.avillach.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.CustomUserDetailService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;

@Configuration
@EnableCaching
public class ApplicationConfig {

    private final CustomUserDetailService customUserDetailService;

    @Autowired
    public ApplicationConfig(CustomUserDetailService customUserDetailService) {
        this.customUserDetailService = customUserDetailService;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(customUserDetailService);
        return provider;
    }

    @Bean("customKeyGenerator")
    public KeyGenerator generator() {
        return new CustomKeyGenerator();
    }

    /**
     * PSAMA's MVC message converters bind with THIS mapper, not Spring Boot's. A user-defined {@code ObjectMapper} bean makes
     * {@code JacksonAutoConfiguration} back off, which also means {@code pic-sure-spring-commons}' {@code StrictWebDeserializationConfig}
     * -- a {@code Jackson2ObjectMapperBuilderCustomizer}, and so reachable only through Boot's builder -- never applies here.
     *
     * <p>That is not a gap: {@code new ObjectMapper()} already has {@code FAIL_ON_UNKNOWN_PROPERTIES} enabled by Jackson default, so PSAMA
     * has always been the strict service the customizer is trying to produce elsewhere. It is Boot that relaxes the setting. The
     * consequence worth knowing is the inverse of the usual one: request types that need to tolerate unknown keys must say so themselves
     * with {@code @JsonIgnoreProperties(ignoreUnknown = true)} -- see {@code OpenAccessValidationRequest} and
     * {@code AuthenticationRequest}, where a strict rejection would be an outage rather than a diagnostic.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
