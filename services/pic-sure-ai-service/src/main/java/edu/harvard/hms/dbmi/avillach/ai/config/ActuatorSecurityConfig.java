package edu.harvard.hms.dbmi.avillach.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

import edu.harvard.hms.dbmi.avillach.commons.security.ActuatorSecurityHelper;
import edu.harvard.hms.dbmi.avillach.commons.security.ActuatorTokenProperties;

/**
 * Gates {@code /actuator/**} behind {@code X-Application-Token} via the shared {@link ActuatorSecurityHelper}, the same pattern
 * {@code pic-sure-operations-service} uses. Registered at {@code @Order(0)} so it wins over {@link WebSecurityConfig}'s permit-all main
 * chain ({@code @Order(10)}) for actuator paths.
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
