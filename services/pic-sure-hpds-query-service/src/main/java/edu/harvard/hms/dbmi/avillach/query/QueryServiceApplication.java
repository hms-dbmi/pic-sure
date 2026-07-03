package edu.harvard.hms.dbmi.avillach.query;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import edu.harvard.hms.dbmi.avillach.commons.error.GatewayExceptionAdvice;

/**
 * Entry point for pic-sure-hpds-query-service: the single HPDS ingress. DB-FREE -- this service owns no database of its own; it
 * persists/loads queries by calling pic-sure-operations-service's internal query API over HTTP (see
 * {@link edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient}).
 *
 * <p>{@link GatewayExceptionAdvice} (from {@code pic-sure-spring-commons}) is imported explicitly because it lives outside this
 * application's base package, so component scanning alone would not pick it up.
 */
@SpringBootApplication
@Import(GatewayExceptionAdvice.class)
public class QueryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(QueryServiceApplication.class, args);
    }
}
