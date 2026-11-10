package edu.harvard.hms.dbmi.avillach.gateway.docs;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
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
 * With open access enabled, the console lists the HPDS open access document under its display name and serves the query service's open
 * group.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "picsure.gateway.security.open-access-enabled=true"
)
class DocsOpenAccessRegistryTest {

    private static final String MINIMAL = "{\"openapi\":\"3.0.1\",\"servers\":[{\"url\":\"http://upstream\"}],\"paths\":{}}";

    static WireMockServer upstream;

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry r) {
        upstream = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        upstream.start();
        r.add("HPDS_QUERY_SERVICE_URL", upstream::baseUrl);
    }

    @AfterAll
    static void stop() {
        upstream.stop();
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper json;

    @LocalServerPort
    int port;

    @Test
    void openAccessDocumentIsListedAndServed() throws Exception {
        upstream.stubFor(get(urlEqualTo("/v3/api-docs/open")).willReturn(okJson(MINIMAL)));

        JsonNode index = json.readTree(rest.getForEntity("http://127.0.0.1:" + port + "/openapi", String.class).getBody());
        JsonNode entry = null;
        for (JsonNode candidate : index) {
            if ("hpds-open-access".equals(candidate.get("name").asText())) {
                entry = candidate;
            }
        }
        assertThat(entry).isNotNull();
        assertThat(entry.get("title").asText()).isEqualTo("Open Queries");
        JsonNode served =
            json.readTree(rest.getForEntity("http://127.0.0.1:" + port + "/openapi/hpds-open-access", String.class).getBody());
        assertThat(served.get("servers").get(0).get("url").asText()).isEqualTo("/picsure");
    }
}
