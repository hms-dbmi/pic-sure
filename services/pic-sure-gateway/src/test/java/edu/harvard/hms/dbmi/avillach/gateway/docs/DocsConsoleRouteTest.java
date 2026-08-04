package edu.harvard.hms.dbmi.avillach.gateway.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.gateway.config.GatewaySecurityProperties;

/**
 * The docs console as a client sees it. NOTHING here sends an {@code Authorization} header: {@code /openapi} and {@code /swagger-ui} are on
 * the gateway's unauthenticated allow-list, so a 200 in these tests is itself the proof that the always-on introspection chain lets the
 * console through -- and a 401 would mean the allow-list entries were dropped.
 *
 * <p>No WireMock stubs are needed for the same reason: an allow-listed path never calls PSAMA.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocsConsoleRouteTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    GatewaySecurityProperties securityProperties;

    @LocalServerPort
    int port;

    private final ObjectMapper json = new ObjectMapper();

    /**
     * Pins the allow-list itself. Both prefixes sat in {@code application.yml} unused since the WAR era; the console is what they were
     * reserved for, and every other test in this class silently depends on them still being there.
     */
    @Test
    void bothConsolePrefixesStayOnTheUnauthenticatedAllowList() {
        assertThat(securityProperties.allowListPrefixes()).contains("/openapi", "/swagger-ui");
    }

    @ParameterizedTest
    @EnumSource(ContractDocument.class)
    void everyDocumentIsServedAsJsonWithoutAToken(ContractDocument document) throws Exception {
        ResponseEntity<String> response = get("/openapi/" + document.fileName());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();

        JsonNode served = json.readTree(response.getBody());
        assertThat(served.path("openapi").asText()).startsWith("3.");
        assertThat(served.path("servers").path(0).path("url").asText("")).isEqualTo(document.publicServer().orElse(""));
    }

    /**
     * The per-service mapping, spelled out as URLs a browser will actually issue. Each expectation is (public prefix) + (a path the
     * committed document declares).
     */
    @Test
    void servedServersResolveDocumentedPathsToRealPublicUrls() throws Exception {
        assertThat(publicUrl(ContractDocument.QUERY_SERVICE, "/hpds/{backend}/v3/query/sync"))
            .isEqualTo("/picsure/hpds/{backend}/v3/query/sync");
        assertThat(publicUrl(ContractDocument.OPERATIONS, "/dataset/named")).isEqualTo("/picsure/operations/dataset/named");
        assertThat(publicUrl(ContractDocument.DICTIONARY, "/concepts")).isEqualTo("/picsure/dictionary/concepts");
        assertThat(publicUrl(ContractDocument.VISUALIZATION, "/v3/bin/continuous")).isEqualTo("/picsure/visualization/v3/bin/continuous");
        assertThat(publicUrl(ContractDocument.AUTH, "/user/me")).isEqualTo("/psama/user/me");
        assertThat(publicUrl(ContractDocument.HPDS, "/PIC-SURE/v3/query")).isEqualTo("/PIC-SURE/v3/query");
    }

    @Test
    void theIndexListsTheSixDocuments() throws Exception {
        ResponseEntity<String> response = get("/openapi");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode documents = json.readTree(response.getBody()).path("documents");
        assertThat(documents).hasSize(6);
        assertThat(documents.path(0).path("file").asText()).isEqualTo(ContractDocument.QUERY_SERVICE.fileName());
    }

    @Test
    void anUnknownDocumentNameIs404() {
        assertThat(get("/openapi/nope.openapi.json").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/openapi/application.yml").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Traversal has no purchase here: an encoded separator is rejected by Spring Security's {@code StrictHttpFirewall} before any handler
     * runs, and even if it were not, the document name is matched against a closed six-entry map rather than joined onto a path. Asserting
     * "not 200" is the point -- which 4xx it is belongs to the firewall, not to this route.
     */
    @Test
    void encodedTraversalNeverReadsAFileOutsideTheDocumentSet() {
        for (
            String attempt : new String[] {"..%2F..%2Fapplication.yml", "%2e%2e%2fapplication.yml", "..%5Capplication.yml",
                "..%2Fopenapi%2Fpic-sure-hpds.openapi.json"}
        ) {
            ResponseEntity<String> response = getRaw("/openapi/" + attempt);
            assertThat(response.getStatusCode().is4xxClientError()).as("traversal attempt %s must not be served", attempt).isTrue();
        }
    }

    @Test
    void theViewerServesTheCheckedInInitializerWithoutAToken() {
        ResponseEntity<String> response = get("/swagger-ui");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(response.getBody()).contains("SwaggerUIBundle");
        // Relative references only -- see the page's own comment on why the /picsure prefix depends on it.
        assertThat(response.getBody()).contains("src=\"swagger-ui/swagger-ui-bundle.js\"").contains("fetch('openapi')")
            .doesNotContain("\"/openapi").doesNotContain("\"/swagger-ui");
    }

    @Test
    void theViewerServesItsWebjarAssets() {
        ResponseEntity<String> css = get("/swagger-ui/swagger-ui.css");
        assertThat(css.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(css.getHeaders().getContentType().toString()).startsWith("text/css");
        assertThat(css.getBody()).contains(".swagger-ui");

        ResponseEntity<String> bundle = get("/swagger-ui/swagger-ui-bundle.js");
        assertThat(bundle.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bundle.getBody()).isNotEmpty();
    }

    @Test
    void anAssetOutsideTheAllowListIs404() {
        assertThat(get("/swagger-ui/swagger-ui.js").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/swagger-ui/index.html").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * A trailing slash would make every relative reference on the page resolve one segment too deep, so it redirects to the canonical form
     * -- with a RELATIVE {@code Location}, because an absolute one would drop the {@code /picsure} prefix httpd strips before the gateway
     * ever sees the request.
     */
    @Test
    void aTrailingSlashRedirectsToTheCanonicalPageRelatively() {
        ResponseEntity<String> response = getRaw("/swagger-ui/");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getFirst("Location")).isEqualTo("../swagger-ui");
    }

    private String publicUrl(ContractDocument document, String documentedPath) throws Exception {
        JsonNode served = json.readTree(get("/openapi/" + document.fileName()).getBody());
        assertThat(served.path("paths").has(documentedPath)).as("%s documents %s", document, documentedPath).isTrue();
        return served.path("servers").path(0).path("url").asText("") + documentedPath;
    }

    private ResponseEntity<String> get(String path) {
        return rest.getForEntity(path, String.class);
    }

    /** Bypasses {@code RestTemplate}'s URI-template encoding so the request goes out with the exact raw path given. */
    private ResponseEntity<String> getRaw(String path) {
        return rest.exchange(URI.create("http://localhost:" + port + path), HttpMethod.GET, null, String.class);
    }
}
