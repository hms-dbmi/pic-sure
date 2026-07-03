package edu.harvard.hms.dbmi.avillach.operations;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import edu.harvard.hms.dbmi.avillach.commons.error.GatewayExceptionAdvice;

/**
 * Entry point for pic-sure-operations-service: the sole owner of the pic-sure MySQL {@code Configuration}, {@code NamedDataset}, and
 * {@code Query} tables. Exposes {@code /configuration/**}, {@code /dataset/**}, and (later) an internal query API.
 *
 * <p>The jakarta {@code pic-sure-api-data} entities/repositories live under {@code edu.harvard.hms.dbmi.avillach.data}, a different base
 * package than this application ({@code edu.harvard.hms.dbmi.avillach.operations}), so they are pulled in explicitly via {@link EntityScan}
 * + {@link EnableJpaRepositories} rather than relying on Spring Boot's default same-package auto-detection.
 *
 * <p>{@link GatewayExceptionAdvice} (from {@code pic-sure-spring-commons}) is imported explicitly for the same reason: it lives outside
 * this application's base package, so component scanning alone would not pick it up.
 */
@SpringBootApplication
@EntityScan("edu.harvard.hms.dbmi.avillach.data.entity")
@EnableJpaRepositories("edu.harvard.hms.dbmi.avillach.data.repository")
@Import(GatewayExceptionAdvice.class)
public class OperationsApplication {

    public static void main(String[] args) {
        SpringApplication.run(OperationsApplication.class, args);
    }
}
