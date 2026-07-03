package edu.harvard.hms.dbmi.avillach.data;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Minimal {@code @SpringBootConfiguration} anchor so {@code @DataJpaTest} can discover the JPA auto-configuration base package
 * ({@code edu.harvard.hms.dbmi.avillach.data}, which covers the {@code entity} and {@code repository} sub-packages) without pulling in a
 * full application module.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class TestJpaConfig {
}
