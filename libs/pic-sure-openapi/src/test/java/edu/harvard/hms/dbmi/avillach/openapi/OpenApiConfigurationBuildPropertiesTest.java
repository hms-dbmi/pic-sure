package edu.harvard.hms.dbmi.avillach.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import io.swagger.v3.oas.models.OpenAPI;

/** With no explicit properties, the artifact id and version from Boot's build info name the document. */
@SpringBootTest(
    classes = {OpenApiTestApplication.class, OpenApiConfigurationBuildPropertiesTest.StubBuild.class},
    properties = "spring.application.name=ignored-name"
)
class OpenApiConfigurationBuildPropertiesTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class StubBuild {

        @Bean
        BuildProperties buildProperties() {
            Properties properties = new Properties();
            properties.setProperty("artifact", "stub-service");
            properties.setProperty("name", "Stub Service");
            properties.setProperty("version", "9.9.9");
            properties.setProperty("group", "edu.harvard.hms.dbmi.avillach");
            return new BuildProperties(properties);
        }
    }

    @Autowired
    private OpenAPI openApi;

    @Test
    void buildInfoNamesTheDocument() {
        assertThat(openApi.getInfo().getTitle()).isEqualTo("stub-service");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("9.9.9");
    }
}
