package edu.harvard.hms.dbmi.avillach.gateway.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** A closed set of six webjar files, resolved under whatever version the webjar on the classpath declares. */
class SwaggerUiAssetsTest {

    private final SwaggerUiAssets assets = new SwaggerUiAssets();

    @Test
    void discoversTheWebjarVersion() {
        assertThat(assets.version()).matches("\\d+\\.\\d+\\.\\d+");
    }

    @Test
    void servesExactlyTheSixListedNamesWithTheirTypes() {
        assertThat(assets.names()).containsExactlyInAnyOrder(
            "swagger-ui.css", "index.css", "swagger-ui-bundle.js", "swagger-ui-standalone-preset.js", "favicon-32x32.png",
            "favicon-16x16.png"
        );
        assertThat(assets.asset("swagger-ui.css")).get().satisfies(a -> {
            assertThat(a.contentType()).isEqualTo(MediaType.valueOf("text/css"));
            assertThat(a.resource().exists()).isTrue();
        });
        assertThat(assets.asset("swagger-ui-bundle.js")).get()
            .satisfies(a -> assertThat(a.contentType()).isEqualTo(MediaType.valueOf("text/javascript")));
        assertThat(assets.asset("favicon-16x16.png")).get().satisfies(a -> assertThat(a.contentType()).isEqualTo(MediaType.IMAGE_PNG));
        for (String name : assets.names()) {
            assertThat(assets.asset(name)).as(name).isPresent();
        }
    }

    @Test
    void everythingElseIsAbsent() {
        for (
            String name : List.of("swagger-ui.js", "index.html", "oauth2-redirect.html", "../../../application.yml", "", "swagger-ui.css/")
        ) {
            assertThat(assets.asset(name)).as(name).isEmpty();
        }
    }
}
