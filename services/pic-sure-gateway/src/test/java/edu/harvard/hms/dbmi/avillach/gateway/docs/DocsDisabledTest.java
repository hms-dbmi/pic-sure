package edu.harvard.hms.dbmi.avillach.gateway.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

import edu.harvard.hms.dbmi.avillach.gateway.config.DocsConfig;

/** With the switch off nothing is wired and nothing is served; the gateway has no catch-all, so every console path is a plain 404. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "picsure.gateway.docs.enabled=false")
class DocsDisabledTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    int port;

    @Test
    void consoleBeansAreAbsent() {
        assertThat(context.getBeansOfType(DocsConfig.class)).isEmpty();
        assertThat(context.getBeansOfType(OpenApiDocumentFetcher.class)).isEmpty();
        assertThat(context.getBeansOfType(SwaggerUiAssets.class)).isEmpty();
    }

    @Test
    void everyConsolePathIs404() {
        for (String path : List.of("/openapi", "/openapi/demo", "/swagger-ui", "/swagger-ui/swagger-ui.css")) {
            assertThat(rest.getForEntity("http://127.0.0.1:" + port + path, String.class).getStatusCode().value()).as(path).isEqualTo(404);
        }
    }
}
