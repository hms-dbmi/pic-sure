package edu.harvard.hms.dbmi.avillach.query.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link SampleProperties} as a bean, mirroring how {@link AggregateConfig} enables {@link AggregateProperties}. */
@Configuration
@EnableConfigurationProperties(SampleProperties.class)
public class SampleConfig {
}
