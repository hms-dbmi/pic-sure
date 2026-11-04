package edu.harvard.hms.dbmi.avillach.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import io.swagger.v3.oas.models.OpenAPI;

/** A service that turns springdoc off still starts, without the enum description beans that depend on springdoc's own. */
@SpringBootTest(classes = OpenApiTestApplication.class, properties = "springdoc.api-docs.enabled=false")
class OpenApiConfigurationDocsDisabledTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextStartsWithoutTheEnumDescriptionBeans() {
        assertThat(context.getBeanNamesForType(EnumConstantDescriptionConverter.class)).isEmpty();
        assertThat(context.getBeanNamesForType(EnumDescriptionCustomizer.class)).isEmpty();
        assertThat(context.getBeansOfType(OpenAPI.class)).hasSize(1);
    }
}
