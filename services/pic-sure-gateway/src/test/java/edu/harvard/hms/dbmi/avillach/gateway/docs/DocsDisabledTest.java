package edu.harvard.hms.dbmi.avillach.gateway.docs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;

import edu.harvard.hms.dbmi.avillach.gateway.config.DocsConfig;

/**
 * The kill switch, from the outside. {@code picsure.gateway.docs.enabled=false} is what a FISMA-constrained or production environment sets
 * to stop publishing an API console; this proves it removes the surface rather than merely hiding a link -- both prefixes 404, because the
 * gateway has no catch-all route to fall into, and the configuration itself is never created.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "picsure.gateway.docs.enabled=false")
class DocsDisabledTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ApplicationContext context;

    @Test
    void theDocsConfigurationIsNotEvenCreated() {
        assertThat(context.getBeanNamesForType(DocsConfig.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OpenApiDocuments.class)).isEmpty();
        assertThat(context.getBeanNamesForType(SwaggerUiAssets.class)).isEmpty();
    }

    @Test
    void everyConsolePathIs404() {
        assertThat(rest.getForEntity("/openapi", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/openapi/" + ContractDocument.DICTIONARY.fileName(), String.class).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/swagger-ui", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity("/swagger-ui/swagger-ui.css", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
