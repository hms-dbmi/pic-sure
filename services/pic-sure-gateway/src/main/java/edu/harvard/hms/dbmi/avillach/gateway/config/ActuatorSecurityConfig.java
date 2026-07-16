package edu.harvard.hms.dbmi.avillach.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import edu.harvard.hms.dbmi.avillach.commons.security.ActuatorSecurityHelper;
import edu.harvard.hms.dbmi.avillach.commons.security.ActuatorTokenProperties;

/**
 * Gates {@code /actuator/**} (all three services) behind {@code X-Application-Token} via the shared {@link ActuatorSecurityHelper}.
 * Registered at {@code @Order(0)} so it wins over the gateway's permit-all main chain ({@link SecurityConfig}, tagged {@code @Order(10)})
 * for actuator paths -- the two chains never compete because {@code EndpointRequest.toAnyEndpoint()} scopes this chain to
 * {@code /actuator/**} only.
 */
@Configuration
@EnableConfigurationProperties(ActuatorTokenProperties.class)
public class ActuatorSecurityConfig {

    @Bean
    @Order(0)
    SecurityFilterChain actuatorChain(HttpSecurity http, ActuatorTokenProperties props) throws Exception {
        return ActuatorSecurityHelper.actuatorChain(http, props);
    }
}
