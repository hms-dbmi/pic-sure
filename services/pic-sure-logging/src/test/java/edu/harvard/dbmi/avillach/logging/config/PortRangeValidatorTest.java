package edu.harvard.dbmi.avillach.logging.config;

import edu.harvard.dbmi.avillach.logging.LoggingServiceApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortRangeValidatorTest {

    private final PortRangeValidator validator = new PortRangeValidator();

    @Test
    void unsetPortDoesNotThrow() {
        MockEnvironment environment = new MockEnvironment();

        assertThatCode(() -> validator.postProcessEnvironment(environment, null)).doesNotThrowAnyException();
    }

    @Test
    void blankPortDoesNotThrow() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("PORT", "   ");

        assertThatCode(() -> validator.postProcessEnvironment(environment, null)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"80", "8080", "1", "65535"})
    void inRangePortsDoNotThrow(String port) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("PORT", port);

        assertThatCode(() -> validator.postProcessEnvironment(environment, null)).doesNotThrowAnyException();
    }

    @Test
    void whitespacePaddedPortIsTrimmedAndDoesNotThrow() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("PORT", " 80 ");

        assertThatCode(() -> validator.postProcessEnvironment(environment, null)).doesNotThrowAnyException();
    }

    @Test
    void zeroPortFailsFast() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("PORT", "0");

        assertThatThrownBy(() -> validator.postProcessEnvironment(environment, null)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("between 1 and 65535");
    }

    @Test
    void tooLargePortFailsFast() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("PORT", "65536");

        assertThatThrownBy(() -> validator.postProcessEnvironment(environment, null)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("between 1 and 65535");
    }

    @Test
    void nonNumericPortFailsFast() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("PORT", "abc");

        assertThatThrownBy(() -> validator.postProcessEnvironment(environment, null)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("valid integer");
    }

    @Test
    void registeredPostProcessorRefusesToStartWithPortZero() {
        // The IllegalStateException is thrown directly from the ApplicationEnvironmentPreparedEvent
        // listener chain and propagates out of run() unwrapped (no BeanCreationException wrapper),
        // so we assert on the thrown exception itself rather than a root cause.
        assertThatThrownBy(
            () -> new SpringApplicationBuilder(LoggingServiceApplication.class).web(WebApplicationType.NONE)
                .run("--picsure.logging.api-key=test-key", "--PORT=0")
        ).isInstanceOf(IllegalStateException.class).hasMessageContaining("between 1 and 65535");
    }
}
