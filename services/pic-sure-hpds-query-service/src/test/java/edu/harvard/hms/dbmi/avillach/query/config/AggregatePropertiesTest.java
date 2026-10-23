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
                "aggregate.visualization-url", "http://viz:8080", "aggregate.obfuscation.consent-threshold", "20",
                "aggregate.obfuscation.chart-threshold", "12", "aggregate.obfuscation.chart-minimum-cohort", "60",
                "aggregate.obfuscation.cross-count-total-only", "false", "aggregate.study-consents-cache-ttl-sec", "42"
            )
        );
        AggregateProperties props = new Binder(source).bind("aggregate", AggregateProperties.class).get();

        assertThat(props.getHpdsOpenUrl()).isEqualTo("http://hpds-open:8080");
        assertThat(props.getHpdsOpenToken()).isEqualTo("open-token");
        assertThat(props.getVisualizationUrl()).isEqualTo("http://viz:8080");
        assertThat(props.getObfuscation().getConsentThreshold()).isEqualTo(20);
        assertThat(props.getObfuscation().getChartThreshold()).isEqualTo(12);
        assertThat(props.getObfuscation().getChartMinimumCohort()).isEqualTo(60);
        assertThat(props.getObfuscation().isCrossCountTotalOnly()).isFalse();
        assertThat(props.getStudyConsentsCacheTtlSec()).isEqualTo(42);
    }

    @Test
    void appliesDefaults() {
        AggregateProperties props = new AggregateProperties();
        assertThat(props.getObfuscation().getConsentThreshold()).isEqualTo(10);
        assertThat(props.getObfuscation().getChartThreshold()).isEqualTo(10);
        assertThat(props.getObfuscation().getChartMinimumCohort()).isEqualTo(50);
        assertThat(props.getObfuscation().isCrossCountTotalOnly()).isTrue();
        assertThat(props.getStudyConsentsCacheTtlSec()).isEqualTo(300);
        assertThat(props.getConnectTimeoutSec()).isEqualTo(10);
        assertThat(props.getReadTimeoutSec()).isEqualTo(60);
    }
}
