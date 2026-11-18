package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamplePropertiesTest {

    @Test
    void blankSaltBecomesARandomValue() {
        SampleProperties properties = new SampleProperties();
        String initial = properties.getSalt();

        properties.setSalt("");
        properties.setSalt("   ");
        properties.setSalt(null);

        assertFalse(initial.isBlank());
        assertEquals(initial, properties.getSalt());
        assertNotEquals(initial, new SampleProperties().getSalt());
    }

    @Test
    void minSizeDefaultsTo100() {
        assertEquals(100, new SampleProperties().getMinSize());
    }

    @Test
    void blankEnvironmentValuesBindToTheDefaults() {
        SampleProperties properties = bind(Map.of("sample.min-size", "", "sample.salt", ""));

        assertEquals(100, properties.getMinSize());
        assertFalse(properties.getSalt().isBlank());
    }

    @Test
    void configuredValuesBind() {
        SampleProperties properties = bind(Map.of("sample.min-size", "25", "sample.salt", "configured-salt"));

        assertEquals(25, properties.getMinSize());
        assertEquals("configured-salt", properties.getSalt());
    }

    @Test
    void minSizeBelowOneIsRefused() {
        IllegalArgumentException direct = assertThrows(IllegalArgumentException.class, () -> new SampleProperties().setMinSize(0));
        assertTrue(direct.getMessage().contains("sample.min-size"));

        BindException atStartup = assertThrows(BindException.class, () -> bind(Map.of("sample.min-size", "-5")));
        assertTrue(rootCause(atStartup).getMessage().contains("must be 1 or more"));
    }

    private static SampleProperties bind(Map<String, String> source) {
        SampleProperties properties = new SampleProperties();
        new Binder(new MapConfigurationPropertySource(source)).bind("sample", Bindable.ofInstance(properties));
        return properties;
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
