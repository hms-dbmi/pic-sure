package edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.service.CaptchaVerifier;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha.CaptchaPurpose.Setting;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts {@link CaptchaConfiguration} on the packaged {@code application.properties} alone, where both purposes default to disabled, and
 * checks that enabling key generation without a CAPTCHA or the explicit opt-in still stops startup.
 */
public class CaptchaPackagedDefaultsTest {

    // runs before the packaged properties load: the shell's API_KEY_* / CAPTCHA_* variables or -D flags must not replace the defaults
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withInitializer(context -> {
        MutablePropertySources sources = context.getEnvironment().getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    }).withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(CaptchaConfiguration.class, CaptchaGateValidator.class)
        .withBean(HttpClient.class, HttpClients::createDefault, definition -> definition.setDestroyMethodName("close"))
        .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    public void testFailsStartupWhenGenerationEnabledWithoutExplicitOptIn() {
        runner.withPropertyValues("api.key.generation.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(CaptchaPurpose.API_KEY.property(Setting.PROVIDER))
                .hasMessageContaining("api.key.allow.ungated.generation");
        });
    }

    @Test
    public void testStartsWhenUngatedGenerationExplicitlyAllowed() {
        runner.withPropertyValues("api.key.generation.enabled=true", "api.key.allow.ungated.generation=true").run(context -> {
            assertThat(context).hasNotFailed();
            CaptchaVerifier verifier = context.getBean("apiKeyCaptchaVerifier", CaptchaVerifier.class);
            assertThat(verifier).isInstanceOf(DisabledCaptchaVerifier.class);
            assertThat(verifier.verify(null, null)).isTrue();
        });
    }

    @Test
    public void testStartsWhenGenerationDisabled() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("apiKeyCaptchaVerifier")).isInstanceOf(DisabledCaptchaVerifier.class);
            assertThat(context.getBean("sessionCaptchaVerifier")).isInstanceOf(DisabledCaptchaVerifier.class);
        });
    }
}
