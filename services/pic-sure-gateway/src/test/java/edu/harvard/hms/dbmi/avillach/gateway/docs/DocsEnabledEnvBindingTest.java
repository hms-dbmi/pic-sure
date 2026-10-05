package edu.harvard.hms.dbmi.avillach.gateway.docs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import edu.harvard.hms.dbmi.avillach.gateway.config.DocsConfig;

/**
 * Pins the placeholder spelling in application.yml: the operator sets {@code GATEWAY_DOCS_ENABLED}, not the Spring property, and a typo in
 * {@code ${GATEWAY_DOCS_ENABLED:true}} would leave the switch inert with every other test still green.
 */
@SpringBootTest(properties = "GATEWAY_DOCS_ENABLED=false")
class DocsEnabledEnvBindingTest {

    @Autowired
    private Environment environment;

    @Autowired
    private ApplicationContext context;

    @Test
    void theOperatorVariableDrivesTheSwitch() {
        assertThat(environment.getProperty("picsure.gateway.docs.enabled", Boolean.class)).isFalse();
        assertThat(context.getBeansOfType(DocsConfig.class)).isEmpty();
    }
}
