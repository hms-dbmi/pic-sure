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
                "aggregate.visualization-url", "http://viz:8080", "aggregate.obfuscation.threshold", "10",
                "aggregate.obfuscation.categorical-threshold", "25", "aggregate.obfuscation.continuous-threshold", "50",
                "aggregate.obfuscation.max-variance", "40"
            )
        );
        AggregateProperties props = new Binder(source).bind("aggregate", AggregateProperties.class).get();

        assertThat(props.getHpdsOpenUrl()).isEqualTo("http://hpds-open:8080");
        assertThat(props.getHpdsOpenToken()).isEqualTo("open-token");
        assertThat(props.getVisualizationUrl()).isEqualTo("http://viz:8080");
        assertThat(props.getObfuscation().getThreshold()).isEqualTo(10);
        assertThat(props.getObfuscation().getCategoricalThreshold()).isEqualTo(25);
        assertThat(props.getObfuscation().getContinuousThreshold()).isEqualTo(50);
        assertThat(props.getObfuscation().getMaxVariance()).isEqualTo(40);
    }

    @Test
    void unsetChartThresholdsTrackTheCountThreshold() {
        // the upgrade hazard: raising the count threshold must not leave charts behind at the old default
        AggregateProperties props = new AggregateProperties();
        props.getObfuscation().setThreshold(50);

        assertThat(props.getObfuscation().getCategoricalThreshold()).isEqualTo(100);
        assertThat(props.getObfuscation().getContinuousThreshold()).isEqualTo(100);
    }

    @Test
    void explicitChartThresholdsOverrideTheDerivedDefault() {
        AggregateProperties props = new AggregateProperties();
        props.getObfuscation().setThreshold(50);
        props.getObfuscation().setCategoricalThreshold(25);

        assertThat(props.getObfuscation().getCategoricalThreshold()).isEqualTo(25);
        assertThat(props.getObfuscation().getContinuousThreshold()).isEqualTo(100); // still derived
    }

    @Test
    void appliesDefaults() {
        AggregateProperties props = new AggregateProperties();
        assertThat(props.getObfuscation().getThreshold()).isEqualTo(10);
        // unset chart thresholds derive from the count threshold at 2x
        assertThat(props.getObfuscation().getCategoricalThreshold()).isEqualTo(20);
        assertThat(props.getObfuscation().getContinuousThreshold()).isEqualTo(20);
        assertThat(props.getObfuscation().getMaxVariance()).isEqualTo(50);
        assertThat(props.getConnectTimeoutSec()).isEqualTo(10);
        assertThat(props.getReadTimeoutSec()).isEqualTo(60);
    }
}
