package edu.harvard.hms.dbmi.avillach.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.swagger.v3.oas.models.OpenAPI;

/** Neither properties nor build info: the application name and the literal "unversioned" stand in. */
@SpringBootTest(classes = OpenApiTestApplication.class, properties = "spring.application.name=fallback-app")
class OpenApiConfigurationFallbackTest {

    @Autowired
    private OpenAPI openApi;

    @Test
    void applicationNameAndUnversionedAreTheLastResort() {
        assertThat(openApi.getInfo().getTitle()).isEqualTo("fallback-app");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("unversioned");
    }
}
