package edu.harvard.dbmi.avillach.logging.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Restores the pre-rewrite PORT guard. Spring accepts server.port=0 as "bind any free port", so a misconfigured PORT=0 would start cleanly
 * on an ephemeral port while the gateway's health probe and route target the configured one. Runs before the web server is created; a
 * bean-based check would execute after onRefresh() has already bound the connector.
 */
public class PortRangeValidator implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String port = environment.getProperty("PORT");
        if (port == null || port.isBlank()) {
            return;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(port.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("PORT must be a valid integer, got: " + port);
        }
        if (parsed < 1 || parsed > 65535) {
            throw new IllegalStateException("PORT must be between 1 and 65535, got: " + port);
        }
    }
}
