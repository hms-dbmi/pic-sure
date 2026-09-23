package edu.harvard.hms.dbmi.avillach.gateway.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import edu.harvard.hms.dbmi.avillach.gateway.config.DocsConfig;

/** With only the UI switch off, the documents keep serving and every Swagger UI path is a plain 404. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "picsure.gateway.docs.ui-enabled=false")
class DocsUiDisabledTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    int port;

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    @Test
    void documentBeansStayAndUiBeansGo() {
        assertThat(context.getBeansOfType(DocsConfig.class)).hasSize(1);
        assertThat(context.getBeansOfType(DocsHandlers.class)).hasSize(1);
        assertThat(context.getBeansOfType(SwaggerUiHandlers.class)).isEmpty();
        assertThat(context.getBeansOfType(SwaggerUiAssets.class)).isEmpty();
    }

    @Test
    void documentsKeepServing() {
        ResponseEntity<String> index = rest.getForEntity(url("/openapi"), String.class);
        assertThat(index.getStatusCode().value()).isEqualTo(200);
        assertThat(index.getHeaders().getContentType()).isNotNull().matches(t -> t.isCompatibleWith(MediaType.APPLICATION_JSON));
        assertThat(rest.getForEntity(url("/openapi/nope"), String.class).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void everyUiPathIs404() {
        for (String path : List.of("/swagger-ui", "/swagger-ui/", "/swagger-ui/swagger-ui.css", "/swagger-ui/swagger-ui-bundle.js")) {
            assertThat(rest.getForEntity(url(path), String.class).getStatusCode().value()).as(path).isEqualTo(404);
        }
    }
}
