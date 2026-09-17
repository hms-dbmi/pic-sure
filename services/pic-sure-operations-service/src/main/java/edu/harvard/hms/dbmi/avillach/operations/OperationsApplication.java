package edu.harvard.hms.dbmi.avillach.operations;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import edu.harvard.hms.dbmi.avillach.commons.error.GatewayExceptionAdvice;

/**
 * <p>The JPA entities and repositories live under this application's own base package ({@code edu.harvard.hms.dbmi.avillach.operations},
 * co-located with each feature's service/mapper), so Spring Boot's default same-package {@code @EntityScan}/repository auto-detection finds
 * them without any explicit scan configuration.
 *
 * <p>{@link GatewayExceptionAdvice} (from {@code pic-sure-spring-commons}) is imported explicitly because it lives outside this
 * application's base package, so component scanning alone would not pick it up.
 */
@SpringBootApplication
@Import(GatewayExceptionAdvice.class)
public class OperationsApplication {

    public static void main(String[] args) {
        SpringApplication.run(OperationsApplication.class, args);
    }
}
