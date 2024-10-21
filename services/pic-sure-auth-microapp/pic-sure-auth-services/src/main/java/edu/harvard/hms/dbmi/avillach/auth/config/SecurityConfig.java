package edu.harvard.hms.dbmi.avillach.auth.config;

import edu.harvard.hms.dbmi.avillach.auth.filter.JWTFilter;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.CacheEvictionService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserService;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

@Configuration
@EnableMethodSecurity(prePostEnabled = false)
@EnableWebSecurity
public class SecurityConfig {

    private final JWTFilter jwtFilter;
    private final CacheEvictionService cacheEvictionService;
    private final AuthenticationProvider authenticationProvider;
    private final UserService userService;
    private final JWTUtil jwtUtil;

    @Autowired
    public SecurityConfig(JWTFilter jwtFilter, AuthenticationProvider authenticationProvider, UserService userService, CacheEvictionService cacheEvictionService, JWTUtil jwtUtil) {
        this.jwtFilter = jwtFilter;
        this.authenticationProvider = authenticationProvider;
        this.userService = userService;
        this.jwtUtil = jwtUtil;
        this.cacheEvictionService = cacheEvictionService;
    }

    @Bean
    public CustomLogoutHandler customLogoutHandler() {
        return new CustomLogoutHandler(userService, cacheEvictionService, jwtUtil);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement((session) -> session.sessionCreationPolicy(STATELESS))
                .authenticationProvider(authenticationProvider)
                .authorizeHttpRequests((authorizeRequests) ->
                    authorizeRequests.requestMatchers(
                                    "/actuator/health",
                                    "/actuator/info",
                                    "/authentication",
                                    "/authentication/**",
                                    "/swagger.yaml",
                                    "/swagger.json",
                                    "/user/me/queryTemplate",
                                    "/user/me/queryTemplate/**",
                                    "/open/validate",
                                    "/logout",
                                    "/cache/**"
                            ).permitAll()
                            .anyRequest().authenticated()
                )
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .logout((logout) -> logout.logoutUrl("/logout").addLogoutHandler(customLogoutHandler()).logoutSuccessHandler((request, response, authentication) -> {
                    // We don't want to redirect to a login page, we just want to return a 200
                    // We leave it to the client to handle the redirect
                    response.setStatus(200);
                }));

        return http.build();
    }

}
