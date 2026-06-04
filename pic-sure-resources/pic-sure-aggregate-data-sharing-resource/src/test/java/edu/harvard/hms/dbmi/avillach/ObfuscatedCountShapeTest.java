package edu.harvard.hms.dbmi.avillach;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

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
    public void aggregateCount_belowThreshold_returnsThresholdMinusOneAndLessThanDisplay() {
        Optional<ObfuscatedCount> result = subject.aggregateCount("3");

        assertTrue("Below-threshold counts must be obfuscated", result.isPresent());
        assertEquals(9, result.get().count());
        assertEquals("< 10", result.get().display());
    }

    @Test
    public void aggregateCount_zero_returnsThresholdMinusOneAndLessThanDisplay() {
        Optional<ObfuscatedCount> result = subject.aggregateCount("0");

        assertTrue("Zero is treated as below-threshold (privacy floor)", result.isPresent());
        assertEquals(9, result.get().count());
        assertEquals("< 10", result.get().display());
    }

    @Test
    public void aggregateCount_atOrAboveThreshold_returnsEmpty() {
        assertTrue(subject.aggregateCount("10").isEmpty());
        assertTrue(subject.aggregateCount("999").isEmpty());
    }

    @Test
    public void aggregateCount_nonNumeric_returnsEmpty() {
        assertTrue(subject.aggregateCount("not-a-number").isEmpty());
    }

    @Test
    public void randomize_appliesVariance_returnsBothNumericAndDisplay() {
        ObfuscatedCount result = subject.randomize("100", 2);

        assertEquals("count must be the numeric obfuscated value", 102, result.count());
        assertEquals("display must carry the formatted variance string", "102 ±3", result.display());
    }

    @Test
    public void randomize_floorsAtThreshold_whenVarianceTakesItBelow() {
        ObfuscatedCount result = subject.randomize("10", -5);

        assertEquals("Floored at threshold so the numeric stays >= threshold", 10, result.count());
        assertEquals("10 ±3", result.display());
    }

    @Test
    public void v3_aggregateCount_belowThreshold_sameContract() {
        Optional<ObfuscatedCount> result = subjectV3.aggregateCount("5");

        assertTrue(result.isPresent());
        assertEquals(9, result.get().count());
        assertEquals("< 10", result.get().display());
    }

    @Test
    public void v3_randomize_sameContract() {
        ObfuscatedCount result = subjectV3.randomize("200", 1);

        assertEquals(201, result.count());
        assertEquals("201 ±3", result.display());
    }
}
