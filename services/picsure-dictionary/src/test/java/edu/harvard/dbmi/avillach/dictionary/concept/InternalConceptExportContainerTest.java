package edu.harvard.dbmi.avillach.dictionary.concept;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import edu.harvard.hms.dbmi.avillach.commons.security.InternalTokenFilter;

/**
 * Calls {@code POST /internal/concepts/export-metadata} over real HTTP on the embedded servlet container, where the container itself maps
 * the token filter to {@code /internal/*}. The canonical path, a path with a matrix parameter and a path with a percent-encoded letter must
 * each be refused without the right token, and the right token on the canonical path must be served.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "picsure.internal.token=" + InternalConceptExportContainerTest.TOKEN
)
class InternalConceptExportContainerTest {

    static final String TOKEN = "inbound-secret";

    private static final String CANONICAL_PATH = "/internal/concepts/export-metadata";
    private static final String BODY =
        "{\"conceptPaths\":[\"\\\\phs000007\\\\pht000021\\\\phv00003844\\\\FL200\\\\\"],\"metaKeys\":[\"drs_uri\"]}";

    @Container
    static final PostgreSQLContainer<?> databaseContainer = new PostgreSQLContainer<>("postgres:16").withReuse(true)
        .withCopyFileToContainer(MountableFile.forClasspathResource("seed.sql"), "/docker-entrypoint-initdb.d/seed.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", databaseContainer::getJdbcUrl);
        registry.add("spring.datasource.username", databaseContainer::getUsername);
        registry.add("spring.datasource.password", databaseContainer::getPassword);
        registry.add("spring.datasource.db", databaseContainer::getDatabaseName);
    }

    @LocalServerPort
    int port;

    private HttpResponse<String> post(String rawPath, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + rawPath))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(BODY));
        if (token != null) {
            request.header(InternalTokenFilter.HEADER, token);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @ParameterizedTest(name = "{0} is refused without the right token")
    @ValueSource(strings = {CANONICAL_PATH, "/internal;x/concepts/export-metadata", "/%69nternal/concepts/export-metadata"})
    void everyFormOfTheInternalPathIsRefusedWithoutTheRightToken(String rawPath) throws Exception {
        HttpResponse<String> withoutToken = post(rawPath, null);
        HttpResponse<String> withWrongToken = post(rawPath, "not-the-secret");

        assertThat(withoutToken.statusCode()).as("%s without a token", rawPath).isNotEqualTo(200);
        assertThat(withoutToken.body()).as("%s without a token", rawPath).doesNotContain("conceptPath");
        assertThat(withWrongToken.statusCode()).as("%s with a wrong token", rawPath).isNotEqualTo(200);
        assertThat(withWrongToken.body()).as("%s with a wrong token", rawPath).doesNotContain("conceptPath");
    }

    @Test
    void theCanonicalPathWithoutATokenIsForbidden() throws Exception {
        assertThat(post(CANONICAL_PATH, null).statusCode()).isEqualTo(403);
        assertThat(post(CANONICAL_PATH, "not-the-secret").statusCode()).isEqualTo(403);
    }

    @Test
    void theRightTokenOnTheCanonicalPathIsServed() throws Exception {
        HttpResponse<String> response = post(CANONICAL_PATH, TOKEN);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"conceptPath\":\"\\\\phs000007\\\\pht000021\\\\phv00003844\\\\FL200\\\\\"");
    }
}
