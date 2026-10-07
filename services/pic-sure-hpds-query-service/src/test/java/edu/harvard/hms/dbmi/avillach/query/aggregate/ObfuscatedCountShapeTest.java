package edu.harvard.hms.dbmi.avillach.query.aggregate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.query.config.AggregateProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that obfuscation produces the {@code {count, display, variance}} shape required for Plotly-compatible visualization values. Both
 * v1 and v3 requests use the same {@link ObfuscationService}, so these tests pin one set of golden values for both paths.
 */
class ObfuscatedCountShapeTest {

    private ObfuscationService subject;

    @BeforeEach
    void setup() {
        subject = new ObfuscationService(new AggregateProperties(), new VisualizationFormatter());
    }

    @Test
    void chartBucket_belowThreshold_returnsZeroCountWithThresholdBandAndLessThanDisplay() {
        ObfuscatedCount result = subject.obfuscateChartBucket(3);

        assertThat(result.count()).isZero();
        assertThat(result.display()).isEqualTo("< 10");
        assertThat(result.variance()).isEqualTo(9);
    }

    @Test
    void chartBucket_atOrAboveThreshold_returnsRoundedCountWithBand() {
        ObfuscatedCount result = subject.obfuscateChartBucket(222);

        assertThat(result.count()).isEqualTo(225);
        assertThat(result.display()).isEqualTo("225 ±5");
        assertThat(result.variance()).isEqualTo(5);
    }

    @Test
    void toInt_acceptsAllJacksonNumberRuntimeTypes() {
        // Jackson may deserialize JSON numbers as Integer, Long, or Double; all numeric representations are accepted.
        assertThat(ObfuscationService.toInt(Integer.valueOf(42))).isEqualTo(42);
        assertThat(ObfuscationService.toInt(Long.valueOf(42L))).isEqualTo(42);
        assertThat(ObfuscationService.toInt(Double.valueOf(42.0))).isEqualTo(42);
        assertThat(ObfuscationService.toInt(Short.valueOf((short) 42))).isEqualTo(42);
        assertThat(ObfuscationService.toInt("42")).isEqualTo(42);
    }

    @Test
    void ofInt_factory_producesStringifiedDisplayAndNullVariance() {
        ObfuscatedCount result = ObfuscatedCount.ofInt(45000);

        assertThat(result.count()).isEqualTo(45000);
        assertThat(result.display()).isEqualTo("45000");
        assertThat(result.variance()).isNull();
    }

    /**
     * Pins the JSON wire shape that visualization-resource (and any other consumer) deserializes against. If field names ever drift, this
     * test fails BEFORE the cross-repo contract silently breaks in production.
     */
    @Test
    void jsonShape_serializesAsCountDisplayAndVarianceFields() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        ObfuscatedCount value = new ObfuscatedCount(222, "222 ±3", 3);

        String json = mapper.writeValueAsString(value);

        assertThat(json).isEqualTo("{\"count\":222,\"display\":\"222 ±3\",\"variance\":3}");

        ObfuscatedCount roundTripped = mapper.readValue(json, ObfuscatedCount.class);
        assertThat(roundTripped).isEqualTo(value);
    }

    /**
     * Exact (authorized) values serialize variance as an explicit null, and below-threshold values pin the {@code {count: 0, variance:
     * threshold-1}} encoding the frontend's band rule depends on.
     */
    @Test
    void jsonShape_nullVarianceAndBelowThresholdEncoding() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();

        assertThat(mapper.writeValueAsString(ObfuscatedCount.ofInt(45000)))
            .isEqualTo("{\"count\":45000,\"display\":\"45000\",\"variance\":null}");

        ObfuscatedCount belowThreshold = subject.obfuscateChartBucket(3);
        assertThat(mapper.writeValueAsString(belowThreshold)).isEqualTo("{\"count\":0,\"display\":\"< 10\",\"variance\":9}");
    }
}
