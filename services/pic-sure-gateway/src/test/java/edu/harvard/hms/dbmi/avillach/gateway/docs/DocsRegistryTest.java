package edu.harvard.hms.dbmi.avillach.gateway.docs;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * One case per registry entry in application.yml: the index lists the service under its name and title, and the served document's
 * {@code servers[0].url} is the public prefix a client must prepend to each path. The upstream is a WireMock serving a minimal document at
 * the entry's docs-path, reached through the same env placeholder the proxy route uses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocsRegistryTest {

    private static final String MINIMAL = "{\"openapi\":\"3.0.1\",\"servers\":[{\"url\":\"http://upstream\"}],\"paths\":{}}";

    static WireMockServer upstream;

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry r) {
        upstream = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        upstream.start();
        r.add("OPERATIONS_SERVICE_URL", upstream::baseUrl);
        r.add("DICTIONARY_URL", upstream::baseUrl);
        r.add("HPDS_QUERY_SERVICE_URL", upstream::baseUrl);
        r.add("VISUALIZATION_URL", upstream::baseUrl);
        r.add("PSAMA_URL", upstream::baseUrl);
    }

    @AfterAll
    static void stop() {
        upstream.stop();
    }

    @BeforeEach
    void reset() {
        upstream.resetAll();
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper json;

    @LocalServerPort
    int port;

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private void assertRegistered(String name, String title, String docsPath, String publicPrefix) throws Exception {
        upstream.stubFor(get(urlEqualTo(docsPath)).willReturn(okJson(MINIMAL)));
        JsonNode index = json.readTree(rest.getForEntity(url("/openapi"), String.class).getBody());
        JsonNode entry = null;
        for (JsonNode candidate : index) {
            if (name.equals(candidate.get("name").asText())) {
                entry = candidate;
            }
        }
        assertThat(entry).as("index entry " + name).isNotNull();
        assertThat(entry.get("title").asText()).isEqualTo(title);
        assertThat(entry.get("url").asText()).isEqualTo("openapi/" + name);
        JsonNode served = json.readTree(rest.getForEntity(url("/openapi/" + name), String.class).getBody());
        assertThat(served.get("servers").get(0).get("url").asText()).isEqualTo(publicPrefix);
    }

    @Test
    void operationsIsRegistered() throws Exception {
        assertRegistered("operations", "Operations service", "/operations/v3/api-docs", "/picsure/operations");
    }

    @Test
    void dictionaryIsRegistered() throws Exception {
        assertRegistered("dictionary", "Dictionary", "/v3/api-docs", "/picsure/dictionary");
    }

    @Test
    void hpdsQueryServiceIsRegistered() throws Exception {
        assertRegistered("hpds-query-service", "HPDS query service", "/v3/api-docs", "/picsure");
    }

    @Test
    void visualizationIsRegistered() throws Exception {
        assertRegistered("visualization", "Visualization service", "/v3/api-docs", "/picsure/visualization");
    }

    @Test
    void psamaIsRegistered() throws Exception {
        assertRegistered("psama", "PSAMA", "/auth/v3/api-docs", "/psama");
    }
}
