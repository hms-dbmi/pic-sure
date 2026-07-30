package edu.harvard.hms.dbmi.avillach.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

import edu.harvard.hms.dbmi.avillach.commons.error.GatewayExceptionAdvice;

/**
 * Entry point for PSAMA.
 *
 * <p>{@link GatewayExceptionAdvice} (from {@code pic-sure-spring-commons}) is imported explicitly because it lives outside this
 * application's base package, so component scanning alone would not pick it up. It is a backstop only: PSAMA's own
 * {@link edu.harvard.hms.dbmi.avillach.auth.exceptions.GlobalExceptionHandler} handles {@code PicsureException} identically, which is what
 * makes the two advices interchangeable regardless of which one Spring consults first.
 */
@SpringBootApplication(exclude = {SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
@EnableJpaRepositories
@EnableScheduling
@Import(GatewayExceptionAdvice.class)
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class);
    }
}
