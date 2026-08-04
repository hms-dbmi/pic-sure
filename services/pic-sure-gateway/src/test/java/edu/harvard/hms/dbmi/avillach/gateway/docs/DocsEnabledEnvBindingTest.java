package edu.harvard.hms.dbmi.avillach.gateway.docs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import edu.harvard.hms.dbmi.avillach.gateway.config.DocsConfig;

/**
 * Pins the OPERATOR-facing half of the kill switch: {@code GATEWAY_DOCS_ENABLED}, the env var an environment actually sets, must reach
 * {@code picsure.gateway.docs.enabled} and take the console down.
 *
 * <p>{@link DocsDisabledTest} sets the Spring property directly, which proves {@code DocsConfig}'s condition works but would keep passing
 * if {@code application.yml}'s {@code ${GATEWAY_DOCS_ENABLED:true}} placeholder were misspelled -- leaving an operator who flips the switch
 * with a console that stays up and no error anywhere. This test supplies the ENV VAR NAME instead and asserts the resolved property, so the
 * placeholder itself is under test. Same shape as {@code GatewaySecurityPropertiesAioBindingTest}, which pins the other env-driven values.
 */
@SpringBootTest(properties = "GATEWAY_DOCS_ENABLED=false")
class DocsEnabledEnvBindingTest {

    @Autowired
    Environment env;

    @Autowired
    ApplicationContext context;

    @Test
    void theEnvVarResolvesThroughTheYamlPlaceholderOntoTheDocsProperty() {
        assertThat(env.getProperty("picsure.gateway.docs.enabled")).isEqualTo("false");
    }

    @Test
    void andActuallyTakesTheConsoleDown() {
        assertThat(context.getBeanNamesForType(DocsConfig.class)).isEmpty();
    }
}
