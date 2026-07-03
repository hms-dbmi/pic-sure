package edu.harvard.hms.dbmi.avillach.query.aggregate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.query.config.AggregateProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ported (JUnit 4 -> JUnit 5) from the WAR's {@code edu.harvard.hms.dbmi.avillach.ObfuscatedCountShapeTest}, which verified that the
 * obfuscation methods produce the rich {@code {count, display, variance}} shape required by the visualization-resource for
 * Plotly-compatible chart values. The WAR exercised both the V1 and V3 {@code AggregateDataSharingResourceRS} copies (identical math in
 * both); here there is a single {@link ObfuscationService}, so this pins the same golden values against that one implementation.
 */
class ObfuscatedCountShapeTest {

    private ObfuscationService subject;

    @BeforeEach
    void setup() {
        AggregateProperties props = new AggregateProperties();
        props.getObfuscation().setThreshold(10);
        props.getObfuscation().setVariance(3);
        props.getObfuscation().setSalt("salt-for-test");
        subject = new ObfuscationService(props, new VisualizationFormatter());
    }

    @Test
    void applyThresholdFloor_belowThreshold_returnsZeroCountWithThresholdBandAndLessThanDisplay() {
        Optional<ObfuscatedCount> result = subject.applyThresholdFloor(3);

        assertThat(result).isPresent();
        assertThat(result.get().count()).isZero();
        assertThat(result.get().display()).isEqualTo("< 10");
        assertThat(result.get().variance()).isEqualTo(9);
    }

    @Test
    void applyThresholdFloor_zero_returnsZeroCountWithThresholdBandAndLessThanDisplay() {
        Optional<ObfuscatedCount> result = subject.applyThresholdFloor(0);

        assertThat(result).isPresent();
        assertThat(result.get().count()).isZero();
        assertThat(result.get().display()).isEqualTo("< 10");
        assertThat(result.get().variance()).isEqualTo(9);
    }

    @Test
    void applyThresholdFloor_atOrAboveThreshold_returnsEmpty() {
        assertThat(subject.applyThresholdFloor(10)).isEmpty();
        assertThat(subject.applyThresholdFloor(999)).isEmpty();
    }

    @Test
    void applyThresholdFloor_stringOverload_nonNumeric_returnsEmpty() {
        assertThat(subject.applyThresholdFloor("not-a-number")).isEmpty();
    }

    @Test
    void randomize_appliesVariance_returnsNumericDisplayAndVariance() {
        ObfuscatedCount result = subject.randomize(100, 2);

        assertThat(result.count()).isEqualTo(102);
        assertThat(result.display()).isEqualTo("102 ±3");
        assertThat(result.variance()).isEqualTo(3);
    }

    @Test
    void randomize_floorsAtThreshold_whenVarianceTakesItBelow() {
        ObfuscatedCount result = subject.randomize(10, -5);

        assertThat(result.count()).isEqualTo(10);
        assertThat(result.display()).isEqualTo("10 ±3");
        assertThat(result.variance()).isEqualTo(3);
    }

    @Test
    void toInt_acceptsAllJacksonNumberRuntimeTypes() {
        // Jackson deserializes JSON numbers into Integer when they fit, Long for larger magnitudes, Double when
        // decimal. The replacement must accept all of these, matching the WAR's toInt() breadth.
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

        ObfuscatedCount belowThreshold = subject.applyThresholdFloor(3).orElseThrow(IllegalStateException::new);
        assertThat(mapper.writeValueAsString(belowThreshold)).isEqualTo("{\"count\":0,\"display\":\"< 10\",\"variance\":9}");
    }
}
