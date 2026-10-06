package edu.harvard.hms.dbmi.avillach.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * Entry point for the PIC-SURE AI-assisted search service. Runs the Bedrock Converse tool-use loop for {@code POST /ai/chat} and reaches
 * PIC-SURE data only as an MCP client against the gateway's MCP endpoint, replaying the caller's bearer JWT untouched.
 *
 * <p>No {@code UserDetailsService} is needed -- this service does no username/password authentication of its own, only a stateless bearer-presence check (see {@code CallerContext}).
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class AiApplication {

    /**
     * Starts the AI-assisted search service.
     *
     * @param args command line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}
