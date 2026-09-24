package edu.harvard.hms.dbmi.avillach.gateway.docs;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * The console's HTTP contract through the real Spring context, against a WireMock upstream. Every request here carries no
 * {@code Authorization} header: the allow-list is what makes the console reachable, and this class proves it end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocsConsoleRouteTest {

    private static final String UPSTREAM_DOCUMENT =
        "{\"openapi\":\"3.0.1\",\"info\":{\"title\":\"demo\",\"version\":\"1\"},\"servers\":[{\"url\":\"http://demo:8080\"}],\"paths\":{\"/things\":{\"get\":{\"summary\":\"List\"}}}}";

    static WireMockServer upstream;

    @DynamicPropertySource
    static void registry(DynamicPropertyRegistry r) {
        upstream = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        upstream.start();
        r.add("picsure.gateway.docs.services[0].name", () -> "demo");
        r.add("picsure.gateway.docs.services[0].title", () -> "Demo service");
        r.add("picsure.gateway.docs.services[0].url", upstream::baseUrl);
        r.add("picsure.gateway.docs.services[0].docs-path", () -> "/demo/v3/api-docs");
        r.add("picsure.gateway.docs.services[0].public-prefix", () -> "/picsure/demo");
        r.add("picsure.gateway.docs.services[1].name", () -> "second");
        r.add("picsure.gateway.docs.services[1].url", upstream::baseUrl);
        r.add("picsure.gateway.docs.services[1].public-prefix", () -> "/picsure/second");
        r.add("picsure.gateway.health.read-timeout-ms", () -> "500");
    }

    @AfterAll
    static void stop() {
        upstream.stop();
    }

    @BeforeEach
    void reset() {
        upstream.resetAll();
        upstream.stubFor(get(urlEqualTo("/demo/v3/api-docs")).willReturn(okJson(UPSTREAM_DOCUMENT)));
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private SwaggerUiAssets assets;

    @LocalServerPort
    int port;

    /** Loopback address, never the name: see {@code DatasetRouteTest}. */
    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private ResponseEntity<String> getRaw(String encodedPath) {
        return rest.exchange(URI.create(url(encodedPath)), HttpMethod.GET, null, String.class);
    }

    @Test
    void indexListsRegisteredServicesInOrder() throws Exception {
        ResponseEntity<String> response = rest.getForEntity(url("/openapi"), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isNotNull().matches(t -> t.isCompatibleWith(MediaType.APPLICATION_JSON));
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache");
        JsonNode index = json.readTree(response.getBody());
        assertThat(index.size()).isEqualTo(2);
        assertThat(index.get(0).get("name").asText()).isEqualTo("demo");
        assertThat(index.get(0).get("title").asText()).isEqualTo("Demo service");
        assertThat(index.get(0).get("url").asText()).isEqualTo("openapi/demo");
        assertThat(index.get(1).get("name").asText()).isEqualTo("second");
        assertThat(index.get(1).get("title").asText()).isEqualTo("second");
    }

    @Test
    void documentIsFetchedLiveWithServersRewritten() throws Exception {
        ResponseEntity<String> response = rest.getForEntity(url("/openapi/demo"), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isNotNull().matches(t -> t.isCompatibleWith(MediaType.APPLICATION_JSON));
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache");
        ObjectNode served = (ObjectNode) json.readTree(response.getBody());
        assertThat(served.get("servers").size()).isEqualTo(1);
        assertThat(served.get("servers").get(0).get("url").asText()).isEqualTo("/picsure/demo");
        ObjectNode expected = (ObjectNode) json.readTree(UPSTREAM_DOCUMENT);
        expected.remove("servers");
        served.remove("servers");
        assertThat(served).isEqualTo(expected);
        upstream.verify(getRequestedFor(urlEqualTo("/demo/v3/api-docs")).withoutHeader("Authorization"));
    }

    @Test
    void unknownDocumentNameIs404InTheGatewayErrorShape() throws Exception {
        ResponseEntity<String> response = rest.getForEntity(url("/openapi/nope"), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        JsonNode body = json.readTree(response.getBody());
        assertThat(body.get("errorType").asText()).isEqualTo("not_found");
        assertThat(body.has("message")).isTrue();
        assertThat(body.has("requestId")).isTrue();
    }

    @Test
    void upstreamFailuresAre502WithTheDocumentedShape() throws Exception {
        upstream.stubFor(get(urlEqualTo("/demo/v3/api-docs")).willReturn(aResponse().withStatus(500)));
        assert502(rest.getForEntity(url("/openapi/demo"), String.class));

        upstream.stubFor(get(urlEqualTo("/demo/v3/api-docs")).willReturn(okJson(UPSTREAM_DOCUMENT).withFixedDelay(2000)));
        assert502(rest.getForEntity(url("/openapi/demo"), String.class));

        upstream.stubFor(get(urlEqualTo("/demo/v3/api-docs")).willReturn(okJson("[1,2]")));
        assert502(rest.getForEntity(url("/openapi/demo"), String.class));

        ResponseEntity<String> index = rest.getForEntity(url("/openapi"), String.class);
        assertThat(json.readTree(index.getBody()).get(0).get("name").asText()).isEqualTo("demo");
    }

    private void assert502(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(json.readTree(response.getBody())).isEqualTo(json.readTree("{\"error\":\"upstream_unavailable\",\"service\":\"demo\"}"));
    }

    @Test
    void encodedTraversalIsRejectedAndNeverServed() {
        for (
            String shape : List.of("..%2F..%2Fapplication.yml", "%2e%2e%2fapplication.yml", "..%5Capplication.yml", "..%2Fopenapi%2Fdemo")
        ) {
            ResponseEntity<String> document = getRaw("/openapi/" + shape);
            assertThat(document.getStatusCode().is4xxClientError()).as("/openapi/" + shape).isTrue();
            assertThat(document.getBody() == null || !document.getBody().contains("\"openapi\"")).as("/openapi/" + shape).isTrue();
            ResponseEntity<String> asset = getRaw("/swagger-ui/" + shape);
            assertThat(asset.getStatusCode().is4xxClientError()).as("/swagger-ui/" + shape).isTrue();
        }
        upstream.verify(0, getRequestedFor(urlEqualTo("/demo/v3/api-docs")));
    }

    @Test
    void viewerIsHtmlAndReferencesOnlyRelativeUrls() {
        ResponseEntity<String> response = rest.getForEntity(url("/swagger-ui"), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isNotNull().matches(t -> t.isCompatibleWith(MediaType.TEXT_HTML));
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache");
        assertThat(response.getBody()).contains("SwaggerUIBundle");
        assertThat(response.getBody()).doesNotContainPattern("[\"'(]/(openapi|swagger-ui)");
    }

    @Test
    void everyAssetThePageReferencesIsOneTheAllowListServes() {
        String page = rest.getForEntity(url("/swagger-ui"), String.class).getBody();
        Matcher m = Pattern.compile("(?:src|href)=\"swagger-ui/([^\"]+)\"").matcher(page);
        int seen = 0;
        while (m.find()) {
            seen++;
            assertThat(assets.names()).as(m.group(1)).contains(m.group(1));
        }
        assertThat(seen).isGreaterThanOrEqualTo(4);
    }

    @Test
    void assetsAreServedWithTheirContentTypesAndOneDayCache() {
        Map<String, String> expected = Map.of(
            "swagger-ui.css", "text/css", "index.css", "text/css", "swagger-ui-bundle.js", "text/javascript",
            "swagger-ui-standalone-preset.js", "text/javascript", "favicon-32x32.png", "image/png", "favicon-16x16.png", "image/png"
        );
        expected.forEach((name, type) -> {
            ResponseEntity<byte[]> response = rest.getForEntity(url("/swagger-ui/" + name), byte[].class);
            assertThat(response.getStatusCode().value()).as(name).isEqualTo(200);
            assertThat(response.getHeaders().getContentType()).as(name).isNotNull()
                .matches(t -> t.isCompatibleWith(MediaType.valueOf(type)));
            assertThat(response.getHeaders().getCacheControl()).as(name).isEqualTo("max-age=86400, public");
            assertThat(response.getBody()).as(name).isNotEmpty();
        });
    }

    @Test
    void unlistedWebjarFilesAre404() {
        for (String name : List.of("swagger-ui.js", "index.html", "oauth2-redirect.html")) {
            assertThat(rest.getForEntity(url("/swagger-ui/" + name), String.class).getStatusCode().value()).as(name).isEqualTo(404);
        }
    }

    @Test
    void trailingSlashRedirectsRelatively() throws Exception {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<Void> response =
            client.send(HttpRequest.newBuilder(URI.create(url("/swagger-ui/"))).GET().build(), HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).contains("../swagger-ui");
    }
}
