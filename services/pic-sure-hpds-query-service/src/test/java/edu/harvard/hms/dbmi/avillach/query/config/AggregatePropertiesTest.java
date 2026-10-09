package edu.harvard.hms.dbmi.avillach.query.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AggregatePropertiesTest {

    @Test
    void bindsNestedObfuscationAndUrls() {
        var source = new MapConfigurationPropertySource(
            Map.of(
                "aggregate.hpds-open-url", "http://hpds-open:8080", "aggregate.hpds-open-token", "open-token",
                "aggregate.visualization-url", "http://viz:8080", "aggregate.obfuscation.threshold", "10", "aggregate.obfuscation.variance",
                "3", "aggregate.obfuscation.salt", "fixed-salt"
            )
        );
        AggregateProperties props = new Binder(source).bind("aggregate", AggregateProperties.class).get();

        assertThat(props.getHpdsOpenUrl()).isEqualTo("http://hpds-open:8080");
        assertThat(props.getHpdsOpenToken()).isEqualTo("open-token");
        assertThat(props.getVisualizationUrl()).isEqualTo("http://viz:8080");
        assertThat(props.getObfuscation().getThreshold()).isEqualTo(10);
        assertThat(props.getObfuscation().getVariance()).isEqualTo(3);
        assertThat(props.getObfuscation().getSalt()).isEqualTo("fixed-salt");
    }

    @Test
    void appliesDefaults() {
        AggregateProperties props = new AggregateProperties();
        assertThat(props.getObfuscation().getThreshold()).isEqualTo(10);
        assertThat(props.getObfuscation().getVariance()).isEqualTo(3);
        assertThat(props.getConnectTimeoutSec()).isEqualTo(10);
        assertThat(props.getReadTimeoutSec()).isEqualTo(60);
    }
}
