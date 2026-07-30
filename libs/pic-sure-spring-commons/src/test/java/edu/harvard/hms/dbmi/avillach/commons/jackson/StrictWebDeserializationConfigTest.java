package edu.harvard.hms.dbmi.avillach.commons.jackson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * The whole point of this config is that a service gets strict request binding by depending on {@code pic-sure-spring-commons} and doing
 * nothing else, so these tests exercise it through Boot's real Jackson auto-configuration rather than by calling the customizer directly.
 */
class StrictWebDeserializationConfigTest {

    private static final String IMPORTS = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, StrictWebDeserializationConfig.class));

    @Test
    void shouldMakeTheAutoConfiguredObjectMapperRejectUnknownProperties() {
        runner.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);

            assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
            assertThrows(UnrecognizedPropertyException.class, () -> mapper.readValue("{\"known\":\"a\",\"typo\":1}", Modelled.class));
        });
    }

    @Test
    void shouldStillBindPayloadsThatOnlyUseModelledProperties() {
        runner.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);

            assertThat(mapper.readValue("{\"known\":\"a\"}", Modelled.class).known()).isEqualTo("a");
        });
    }

    /**
     * A record that opts out with {@code @JsonIgnoreProperties(ignoreUnknown = true)} must keep tolerating unknown keys: the global feature
     * is the default, not an override. Contracts that read third-party payloads (PSAMA introspection, audit intake) rely on this.
     */
    @Test
    void shouldLetIndividualTypesOptOutOfStrictness() {
        runner.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);

            assertThat(mapper.readValue("{\"known\":\"a\",\"typo\":1}", Tolerant.class).known()).isEqualTo("a");
        });
    }

    /**
     * Registration must be via the auto-configuration imports file, not a per-application {@code @Import}: a service that forgets the
     * import would silently keep lenient binding, which is exactly the failure mode this config exists to remove.
     */
    @Test
    void shouldBeRegisteredAsAnAutoConfiguration() throws IOException {
        assertThat(importedAutoConfigurations()).contains(StrictWebDeserializationConfig.class.getName());
    }

    private List<String> importedAutoConfigurations() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(IMPORTS)) {
            assertThat(in).as("missing " + IMPORTS).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
        }
    }

    record Modelled(String known) {
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Tolerant(String known) {
    }
}
