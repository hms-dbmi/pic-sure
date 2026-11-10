package edu.harvard.hms.dbmi.avillach.query.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link QueryRowLockProperties} as a bean, mirroring how {@link SampleConfig} enables {@link SampleProperties}. */
@Configuration
@EnableConfigurationProperties(QueryRowLockProperties.class)
public class QueryRowLockConfig {
}
