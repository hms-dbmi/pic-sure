package edu.harvard.hms.dbmi.avillach.gateway.docs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

/** Pins the placeholder spelling: the operator sets GATEWAY_DOCS_UI_ENABLED, and a typo in application.yml would leave it inert. */
@SpringBootTest(properties = "GATEWAY_DOCS_UI_ENABLED=false")
class DocsUiEnabledEnvBindingTest {

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DocsProperties props;

    @Test
    void theOperatorVariableDrivesTheUiSwitch() {
        assertThat(environment.getProperty("picsure.gateway.docs.ui-enabled", Boolean.class)).isFalse();
        assertThat(props.uiEnabled()).isFalse();
        assertThat(props.enabled()).isTrue();
        assertThat(context.getBeansOfType(SwaggerUiHandlers.class)).isEmpty();
        assertThat(context.getBeansOfType(DocsHandlers.class)).hasSize(1);
    }
}
