package edu.harvard.dbmi.avillach.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pins the {@code {count, display, variance}} wire shape the query service and the visualization service exchange, plus the record's
 * accessors and factories. A plain {@link ObjectMapper} is used so a renamed or dropped field fails here before the cross-service contract
 * breaks in production.
 */
public class ObfuscatedCountTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void carriesFieldsAndValueEquality() {
        ObfuscatedCount a = new ObfuscatedCount(0, "< 10", 9);
        assertEquals(new ObfuscatedCount(0, "< 10", 9), a);
        assertEquals(0, a.count());
        assertEquals("< 10", a.display());
        assertEquals(Integer.valueOf(9), a.variance());
    }

    @Test
    public void ofIntHasNullVariance() {
        ObfuscatedCount c = ObfuscatedCount.ofInt(42);
        assertEquals("42", c.display());
        assertNull(c.variance());
    }

    @Test
    public void twoArgConstructorHasNullVariance() {
        assertNull(new ObfuscatedCount(5, "5").variance());
    }

    @Test
    public void serializesCountDisplayVariance() throws JsonProcessingException {
        assertEquals(
            "{\"count\":222,\"display\":\"222 ±3\",\"variance\":3}", mapper.writeValueAsString(new ObfuscatedCount(222, "222 ±3", 3))
        );
    }

    @Test
    public void serializesExactValuesWithNullVariance() throws JsonProcessingException {
        assertEquals("{\"count\":45000,\"display\":\"45000\",\"variance\":null}", mapper.writeValueAsString(ObfuscatedCount.ofInt(45000)));
    }

    @Test
    public void deserializesFromTheObfuscatedShape() throws JsonProcessingException {
        ObfuscatedCount value = mapper.readValue("{\"count\":0,\"display\":\"< 10\",\"variance\":9}", ObfuscatedCount.class);
        assertEquals(new ObfuscatedCount(0, "< 10", 9), value);
    }
}
