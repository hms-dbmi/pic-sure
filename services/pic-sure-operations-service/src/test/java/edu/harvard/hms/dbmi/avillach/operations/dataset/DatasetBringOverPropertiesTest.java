package edu.harvard.hms.dbmi.avillach.operations.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Covers the default of {@link DatasetBringOverProperties}, on the record and through the binder that fills it from configuration at
 * startup, and checks that the {@code application.yml} the tests run with leaves the step off.
 */
class DatasetBringOverPropertiesTest {

    private static final String ENABLED = "picsure.operations.dataset-bring-over.enabled";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withUserConfiguration(Binding.class);

    @Test
    void theStepIsOffWhenTheSettingIsAbsent() {
        contextRunner.run(context -> assertThat(context.getBean(DatasetBringOverProperties.class).enabled()).isFalse());
    }

    @Test
    void theStepIsOffWhenTheSettingIsBlank() {
        contextRunner.withPropertyValues(ENABLED + "=")
            .run(context -> assertThat(context.getBean(DatasetBringOverProperties.class).enabled()).isFalse());
    }

    @Test
    void theSettingTurnsTheStepOn() {
        contextRunner.withPropertyValues(ENABLED + "=true")
            .run(context -> assertThat(context.getBean(DatasetBringOverProperties.class).enabled()).isTrue());
    }

    @Test
    void theTestConfigurationLeavesTheStepOff() {
        contextRunner.withInitializer(new ConfigDataApplicationContextInitializer())
            .run(context -> assertThat(context.getBean(DatasetBringOverProperties.class).enabled()).isFalse());
    }

    @Test
    void theRecordIsOffWithoutABinder() {
        assertThat(new DatasetBringOverProperties(null).enabled()).isFalse();
        assertThat(new DatasetBringOverProperties(true).enabled()).isTrue();
    }

    /** Registers the properties the way {@link DatasetBringOver} does, without the step or a database. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DatasetBringOverProperties.class)
    static class Binding {
    }
}
