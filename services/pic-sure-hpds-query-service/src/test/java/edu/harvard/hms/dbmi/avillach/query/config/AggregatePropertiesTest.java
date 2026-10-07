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
                "aggregate.visualization-url", "http://viz:8080", "aggregate.obfuscation.consent-threshold", "6",
                "aggregate.obfuscation.chart-minimum-cohort", "60"
            )
        );
        AggregateProperties props = new Binder(source).bind("aggregate", AggregateProperties.class).get();

        assertThat(props.getHpdsOpenUrl()).isEqualTo("http://hpds-open:8080");
        assertThat(props.getHpdsOpenToken()).isEqualTo("open-token");
        assertThat(props.getVisualizationUrl()).isEqualTo("http://viz:8080");
        assertThat(props.getObfuscation().getConsentThreshold()).isEqualTo(6);
        assertThat(props.getObfuscation().getChartMinimumCohort()).isEqualTo(60);
    }

    @Test
    void appliesDefaults() {
        AggregateProperties props = new AggregateProperties();
        assertThat(props.getObfuscation().getConsentThreshold()).isEqualTo(5);
        assertThat(props.getObfuscation().getChartMinimumCohort()).isEqualTo(50);
        assertThat(props.getConnectTimeoutSec()).isEqualTo(10);
        assertThat(props.getReadTimeoutSec()).isEqualTo(60);
    }
}
