package edu.harvard.hms.dbmi.avillach.gateway.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** The viewer's asset plumbing: version discovery, the closed asset set, and the one hand-written file. */
class SwaggerUiAssetsTest {

    private final SwaggerUiAssets assets = new SwaggerUiAssets();

    /**
     * The webjar version is read from the webjar's own {@code pom.properties}, not duplicated in Java, so a platform-BOM bump needs no code
     * change. This asserts the discovery actually resolved onto a directory that exists.
     */
    @Test
    void theWebjarVersionIsDiscoveredFromTheJarItself() {
        assertThat(assets.webjarDirectory()).matches("META-INF/resources/webjars/swagger-ui/\\d+\\.\\d+\\.\\d+/");
        assertThat(assets.asset("swagger-ui.css")).isPresent();
    }

    @Test
    void onlyTheFilesTheInitializerLoadsAreServable() {
        assertThat(assets.asset("swagger-ui.css")).isPresent();
        assertThat(assets.asset("index.css")).isPresent();
        assertThat(assets.asset("swagger-ui-bundle.js")).isPresent();
        assertThat(assets.asset("swagger-ui-standalone-preset.js")).isPresent();
        assertThat(assets.asset("favicon-16x16.png")).isPresent();
        assertThat(assets.asset("favicon-32x32.png")).isPresent();

        // Present in the webjar, deliberately not exposed.
        assertThat(assets.asset("swagger-ui.js")).isEmpty();
        assertThat(assets.asset("index.html")).isEmpty();
        assertThat(assets.asset("oauth2-redirect.html")).isEmpty();
        // Never a path, only a name.
        assertThat(assets.asset("../../../application.yml")).isEmpty();
        assertThat(assets.asset("")).isEmpty();
    }

    /**
     * Drift guard between the two halves of the viewer: every asset the checked-in page references must be one the allow-list serves, or
     * the console renders unstyled with a dead picker.
     */
    @Test
    void everyAssetTheInitializerReferencesIsServable() throws IOException {
        String page = new String(assets.initializer().getContentAsByteArray(), StandardCharsets.UTF_8);

        for (
            String reference : new String[] {"swagger-ui.css", "index.css", "swagger-ui-bundle.js", "swagger-ui-standalone-preset.js",
                "favicon-32x32.png", "favicon-16x16.png"}
        ) {
            assertThat(page).as("initializer references %s", reference).contains("swagger-ui/" + reference);
            assertThat(assets.asset(reference)).as("%s is servable", reference).isPresent();
        }
    }
}
