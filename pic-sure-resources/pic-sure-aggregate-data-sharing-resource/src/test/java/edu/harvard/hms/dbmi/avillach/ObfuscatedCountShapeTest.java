package edu.harvard.hms.dbmi.avillach;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Before;
import org.junit.Test;

/**
 * Verifies that the obfuscation methods produce the rich {count, display} shape
 * required by the visualization-resource for Plotly-compatible chart values.
 *
 * Exercises both V1 and V3 implementations since they carry parallel copies.
 */
public class ObfuscatedCountShapeTest {

    private AggregateDataSharingResourceRS subject;
    private AggregateDataSharingResourceRSV3 subjectV3;

    @Before
    public void setup() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.getTargetPicsureObfuscationThreshold()).thenReturn(10);
        when(props.getTargetPicsureObfuscationVariance()).thenReturn(3);
        when(props.getTargetPicsureObfuscationSalt()).thenReturn("salt-for-test");
        subject = new AggregateDataSharingResourceRS(props);
        subjectV3 = new AggregateDataSharingResourceRSV3(props);
    }

    @Test
    public void applyThresholdFloor_belowThreshold_returnsZeroCountWithThresholdBandAndLessThanDisplay() {
        Optional<ObfuscatedCount> result = subject.applyThresholdFloor(3);

        assertTrue("Below-threshold counts must be obfuscated", result.isPresent());
        assertEquals(0, result.get().count());
        assertEquals("< 10", result.get().display());
        assertEquals("Band [max(0, 0-9), 0+9] renders 0..threshold-1", Integer.valueOf(9), result.get().variance());
    }

    @Test
    public void applyThresholdFloor_zero_returnsZeroCountWithThresholdBandAndLessThanDisplay() {
        Optional<ObfuscatedCount> result = subject.applyThresholdFloor(0);

        assertTrue("Zero is treated as below-threshold (privacy floor)", result.isPresent());
        assertEquals(0, result.get().count());
        assertEquals("< 10", result.get().display());
        assertEquals(Integer.valueOf(9), result.get().variance());
    }

    @Test
    public void applyThresholdFloor_atOrAboveThreshold_returnsEmpty() {
        assertTrue(subject.applyThresholdFloor(10).isEmpty());
        assertTrue(subject.applyThresholdFloor(999).isEmpty());
    }

    @Test
    public void applyThresholdFloor_stringOverload_nonNumeric_returnsEmpty() {
        assertTrue(subject.applyThresholdFloor("not-a-number").isEmpty());
    }

    @Test
    public void randomize_appliesVariance_returnsNumericDisplayAndVariance() {
        ObfuscatedCount result = subject.randomize(100, 2);

        assertEquals("count must be the numeric obfuscated value", 102, result.count());
        assertEquals("display must carry the formatted variance string", "102 ±3", result.display());
        assertEquals("variance must carry the configured band half-width", Integer.valueOf(3), result.variance());
    }

    @Test
    public void randomize_floorsAtThreshold_whenVarianceTakesItBelow() {
        ObfuscatedCount result = subject.randomize(10, -5);

        assertEquals("Floored at threshold so the numeric stays >= threshold", 10, result.count());
        assertEquals("10 ±3", result.display());
        assertEquals(Integer.valueOf(3), result.variance());
    }

    @Test
    public void v3_applyThresholdFloor_belowThreshold_sameContract() {
        Optional<ObfuscatedCount> result = subjectV3.applyThresholdFloor(5);

        assertTrue(result.isPresent());
        assertEquals(0, result.get().count());
        assertEquals("< 10", result.get().display());
        assertEquals(Integer.valueOf(9), result.get().variance());
    }

    @Test
    public void v3_randomize_sameContract() {
        ObfuscatedCount result = subjectV3.randomize(200, 1);

        assertEquals(201, result.count());
        assertEquals("201 ±3", result.display());
        assertEquals(Integer.valueOf(3), result.variance());
    }

    @Test
    public void toInt_acceptsAllJacksonNumberRuntimeTypes() {
        // Jackson deserializes JSON numbers into Integer when they fit, Long for larger magnitudes, Double when
        // decimal. The previous innerValue.toString() shape silently accepted all of these via Integer.parseInt
        // (well, except Double — which it would have crashed on anyway). The replacement must not narrow.
        assertEquals(42, AggregateDataSharingResourceRS.toInt(Integer.valueOf(42)));
        assertEquals(42, AggregateDataSharingResourceRS.toInt(Long.valueOf(42L)));
        assertEquals(42, AggregateDataSharingResourceRS.toInt(Double.valueOf(42.0)));
        assertEquals(42, AggregateDataSharingResourceRS.toInt(Short.valueOf((short) 42)));
        assertEquals(42, AggregateDataSharingResourceRS.toInt("42"));
    }

    @Test
    public void ofInt_factory_producesStringifiedDisplayAndNullVariance() {
        ObfuscatedCount result = ObfuscatedCount.ofInt(45000);

        assertEquals(45000, result.count());
        assertEquals("45000", result.display());
        assertEquals("Exact (authorized) values carry no uncertainty band", null, result.variance());
    }

    /**
     * Pins the JSON wire shape that visualization-resource (and any other consumer) deserializes against.
     * If field names ever drift (e.g. someone renames the record component), this test fails BEFORE the
     * cross-repo contract silently breaks in production.
     */
    @Test
    public void jsonShape_serializesAsCountDisplayAndVarianceFields() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        ObfuscatedCount value = new ObfuscatedCount(222, "222 ±3", 3);

        String json = mapper.writeValueAsString(value);

        // Exact-match assertion on serialized form. Field order is conventional for clarity but not load-bearing
        // for consumers; we accept either ordering by JSON equality below.
        assertEquals("{\"count\":222,\"display\":\"222 ±3\",\"variance\":3}", json);

        // Round-trip back through the mapper, asserting all fields survived.
        ObfuscatedCount roundTripped = mapper.readValue(json, ObfuscatedCount.class);
        assertEquals(value, roundTripped);
    }

    /**
     * Exact (authorized) values serialize variance as an explicit null, and below-threshold values pin
     * the {count: 0, variance: threshold-1} encoding the frontend's band rule depends on.
     */
    @Test
    public void jsonShape_nullVarianceAndBelowThresholdEncoding() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();

        assertEquals("{\"count\":45000,\"display\":\"45000\",\"variance\":null}", mapper.writeValueAsString(ObfuscatedCount.ofInt(45000)));

        ObfuscatedCount belowThreshold = subject.applyThresholdFloor(3).orElseThrow(IllegalStateException::new);
        assertEquals("{\"count\":0,\"display\":\"< 10\",\"variance\":9}", mapper.writeValueAsString(belowThreshold));
    }
}
