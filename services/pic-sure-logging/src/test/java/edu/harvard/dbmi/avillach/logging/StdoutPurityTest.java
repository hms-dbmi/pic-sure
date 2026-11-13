package edu.harvard.dbmi.avillach.logging;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stdout is the audit-JSON channel that Splunk parses. Nothing but JSON may appear there. The Spring banner prints to System.out before
 * logging initializes, which is why spring.main.banner-mode must be off.
 */
class StdoutPurityTest {

    @Test
    void startupWritesNothingNonJsonToStdout() {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));

        try (
            ConfigurableApplicationContext ignored =
                new SpringApplicationBuilder(LoggingServiceApplication.class).web(WebApplicationType.NONE)
                    // Must be a command-line arg, NOT .properties(...). SpringApplicationBuilder.properties()
                    // populates `defaultProperties`, the LOWEST-precedence source, which loses to
                    // application.yml's `${LOGGING_API_KEY:}` -> "" and fails the context with
                    // "LOGGING_API_KEY environment variable is required". Command-line args outrank
                    // application.yml.
                    .run("--picsure.logging.api-key=test-key")
        ) {
            // starting the context is the whole exercise
        } finally {
            System.setOut(original);
        }

        List<String> lines = captured.toString(StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank()).toList();

        assertThat(lines).withFailMessage("stdout must carry only audit JSON, but got:%n%s", String.join("\n", lines))
            .allSatisfy(line -> assertThat(line).startsWith("{"));
    }
}
