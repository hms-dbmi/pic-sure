package edu.harvard.dbmi.avillach.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pins the JSON the visualization service and the query service exchange for binned continuous counts. The mapper is a plain
 * {@link ObjectMapper}, which rejects unknown properties by default, so the tolerance tests pass only because of the record's own
 * annotation.
 */
public class ContinuousBinningResponseTest {

    private static final String WIRE =
        "{\"bins\":{\"\\\\demographics\\\\AGE\\\\\":{\"20.0 - 50.0\":42,\"50.0 +\":95},\"\\\\measurements\\\\bmi\\\\\":{\"27.5\":9}}}";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void serializesTheMapUnderBinsInInsertionOrder() throws JsonProcessingException {
        Map<String, Integer> age = new LinkedHashMap<>();
        age.put("20.0 - 50.0", 42);
        age.put("50.0 +", 95);
        Map<String, Integer> bmi = new LinkedHashMap<>();
        bmi.put("27.5", 9);
        Map<String, Map<String, Integer>> bins = new LinkedHashMap<>();
        bins.put("\\demographics\\AGE\\", age);
        bins.put("\\measurements\\bmi\\", bmi);

        assertEquals(WIRE, mapper.writeValueAsString(new ContinuousBinningResponse(bins)));
    }

    @Test
    public void deserializesKeepingConceptAndBinOrder() throws JsonProcessingException {
        ContinuousBinningResponse response = mapper.readValue(WIRE, ContinuousBinningResponse.class);

        assertEquals(List.of("\\demographics\\AGE\\", "\\measurements\\bmi\\"), new ArrayList<>(response.bins().keySet()));
        assertEquals(List.of("20.0 - 50.0", "50.0 +"), new ArrayList<>(response.bins().get("\\demographics\\AGE\\").keySet()));
        assertEquals(Integer.valueOf(95), response.bins().get("\\demographics\\AGE\\").get("50.0 +"));
    }

    @Test
    public void ignoresPropertiesItDoesNotName() throws JsonProcessingException {
        ContinuousBinningResponse response =
            mapper.readValue("{\"bins\":{\"\\\\demographics\\\\AGE\\\\\":{\"45.0\":12}},\"binCount\":1}", ContinuousBinningResponse.class);

        assertEquals(Map.of("\\demographics\\AGE\\", Map.of("45.0", 12)), response.bins());
    }

    @Test
    public void readsTheBareMapOfAnOlderProducerAsNoBins() throws JsonProcessingException {
        ContinuousBinningResponse response =
            mapper.readValue("{\"\\\\demographics\\\\AGE\\\\\":{\"45.0\":12}}", ContinuousBinningResponse.class);

        assertNull(response.bins());
    }
}
