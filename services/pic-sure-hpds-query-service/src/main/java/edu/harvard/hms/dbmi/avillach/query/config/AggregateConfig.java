package edu.harvard.hms.dbmi.avillach.query.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link AggregateProperties} as a bean, mirroring how {@link HpdsClientConfig} enables {@link HpdsProperties}.
 * {@code ObfuscationService} (query.aggregate) is a component-scanned {@code @Service} that depends on {@link AggregateProperties} being
 * bound from config, so this wiring has to exist even though the HTTP orchestration around it (visualization RestClient, controllers) is a
 * later unit.
 */
@Configuration
@EnableConfigurationProperties(AggregateProperties.class)
public class AggregateConfig {
}
