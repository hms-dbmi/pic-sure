package edu.harvard.hms.dbmi.avillach.operations.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Covers the defaults and the range checks of {@link ResultTypeBackfillProperties}, both on the record and through the binder that fills it
 * from configuration at startup, and checks that the {@code application.yml} the tests run with turns the pass off.
 */
class ResultTypeBackfillPropertiesTest {

    private static final String PREFIX = "picsure.operations.result-type-backfill";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withUserConfiguration(Binding.class);

    @Test
    void settingsThatAreAbsentTakeTheDefaults() {
        contextRunner.run(context -> {
            ResultTypeBackfillProperties bound = context.getBean(ResultTypeBackfillProperties.class);
            assertThat(bound).isEqualTo(new ResultTypeBackfillProperties(true, 200, Duration.ofMillis(250)));
        });
    }

    @Test
    void settingsThatAreBlankTakeTheDefaults() {
        contextRunner.withPropertyValues(PREFIX + ".enabled=", PREFIX + ".batch-size=", PREFIX + ".pause=").run(context -> {
            ResultTypeBackfillProperties bound = context.getBean(ResultTypeBackfillProperties.class);
            assertThat(bound).isEqualTo(new ResultTypeBackfillProperties(true, 200, Duration.ofMillis(250)));
        });
    }

    @Test
    void configuredSettingsAreBound() {
        contextRunner.withPropertyValues(PREFIX + ".enabled=false", PREFIX + ".batch-size=1", PREFIX + ".pause=0").run(context -> {
            ResultTypeBackfillProperties bound = context.getBean(ResultTypeBackfillProperties.class);
            assertThat(bound).isEqualTo(new ResultTypeBackfillProperties(false, 1, Duration.ZERO));
        });
    }

    @Test
    void theTestConfigurationTurnsThePassOff() {
        contextRunner.withInitializer(new ConfigDataApplicationContextInitializer())
            .run(context -> assertThat(context.getBean(ResultTypeBackfillProperties.class).enabled()).isFalse());
    }

    @Test
    void aBatchSizeBelowOneStopsTheStartupAndNamesTheSetting() {
        contextRunner.withPropertyValues(PREFIX + ".batch-size=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalArgumentException.class)
                .hasMessage(PREFIX + ".batch-size must be at least 1, but is 0");
        });
    }

    @Test
    void aNegativePauseStopsTheStartupAndNamesTheSetting() {
        contextRunner.withPropertyValues(PREFIX + ".pause=-1ms").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalArgumentException.class)
                .hasMessage(PREFIX + ".pause must not be negative, but is PT-0.001S");
        });
    }

    @Test
    void theRecordRefusesTheSameValuesWithoutABinder() {
        assertThatThrownBy(() -> new ResultTypeBackfillProperties(true, -5, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage(PREFIX + ".batch-size must be at least 1, but is -5");
        assertThatThrownBy(() -> new ResultTypeBackfillProperties(true, 1, Duration.ofSeconds(-2)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage(PREFIX + ".pause must not be negative, but is PT-2S");
    }

    /** Registers the properties the way {@link ResultTypeBackfill} does, without the pass or a database. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ResultTypeBackfillProperties.class)
    static class Binding {
    }
}
