package edu.harvard.hms.dbmi.avillach.query.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * Covers the {@code sample.enabled} binding, both on the bare properties class and through the {@code ${SAMPLE_ENABLED:false}} placeholder
 * of the main {@code application.yml}, which the test classpath shadows and is therefore loaded here from its source path.
 */
class SamplePropertiesTest {

    private static final String VARIABLE = "SAMPLE_ENABLED";

    /** Boots {@link SampleConfig} against the main {@code application.yml} with {@code SAMPLE_ENABLED} holding the given value. */
    private static ApplicationContextRunner runnerWithVariable(String value) {
        return runner().withInitializer(
            context -> context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("variables", Map.of(VARIABLE, value)))
        );
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withInitializer(context -> {
            try {
                new YamlPropertySourceLoader().load("main-application-yml", new FileSystemResource("src/main/resources/application.yml"))
                    .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }).withUserConfiguration(SampleConfig.class);
    }

    @Test
    void enabledDefaultsToFalse() {
        assertThat(new SampleProperties().isEnabled()).isFalse();
    }

    @Test
    void aNullSettingIsOff() {
        SampleProperties props = new SampleProperties();
        props.setEnabled(null);

        assertThat(props.isEnabled()).isFalse();
    }

    @Test
    void bindsEnabled() {
        var source = new MapConfigurationPropertySource(Map.of("sample.enabled", "true"));

        SampleProperties props = new Binder(source).bind("sample", SampleProperties.class).get();

        assertThat(props.isEnabled()).isTrue();
    }

    @Test
    void anUnsetVariableBindsFalseThroughTheMainYaml() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("sample.enabled")).isEqualTo("false");
            assertThat(context.getBean(SampleProperties.class).isEnabled()).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t"})
    void aBlankVariableBindsFalseThroughTheMainYaml(String blank) {
        runnerWithVariable(blank).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("sample.enabled")).isEqualTo(blank);
            assertThat(context.getBean(SampleProperties.class).isEnabled()).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE"})
    void aTrueVariableBindsTrueThroughTheMainYaml(String value) {
        runnerWithVariable(value).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SampleProperties.class).isEnabled()).isTrue();
        });
    }

    @Test
    void aFalseVariableBindsFalseThroughTheMainYaml() {
        runnerWithVariable("false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SampleProperties.class).isEnabled()).isFalse();
        });
    }

    @Test
    void anInvalidVariableFailsStartup() {
        runnerWithVariable("banana").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining("sample.enabled");
            assertThat(context.getStartupFailure()).hasCauseInstanceOf(BindException.class);
        });
    }
}
