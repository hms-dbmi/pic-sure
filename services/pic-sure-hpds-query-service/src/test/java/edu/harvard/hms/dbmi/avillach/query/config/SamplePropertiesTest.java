package edu.harvard.hms.dbmi.avillach.query.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class SamplePropertiesTest {

    @Test
    void enabledDefaultsToFalse() {
        assertThat(new SampleProperties().isEnabled()).isFalse();
    }

    @Test
    void bindsEnabled() {
        var source = new MapConfigurationPropertySource(Map.of("sample.enabled", "true"));

        SampleProperties props = new Binder(source).bind("sample", SampleProperties.class).get();

        assertThat(props.isEnabled()).isTrue();
    }
}
