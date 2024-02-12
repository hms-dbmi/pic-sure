package edu.harvard.hms.dbmi.avillach.resource.visualization.service;

import edu.harvard.dbmi.avillach.util.VisualizationUtil;
import org.junit.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class VisualizationUtilTests {

    @Test
    @DisplayName("Test limitKeySize")
    public void testLimitKeySizeUniqueness() {
        Map<String, Integer> axisMap = new HashMap<>(Map.of(
                "Disease-Specific (Asthma, Allergy and Inflammation, PUB)", 1,
                "Disease-Specific (Asthma, Allergy and Inflammation, PUB, NPU)", 1,
                "Disease-Specific (Asthma, Allergy and Inflammation, NPU)", 1,
                "Disease-Specific (Asthma, Allergy and Inflammation)", 1
        ));

        Map<String, Integer> actual = VisualizationUtil.limitKeySize(axisMap);

        Map<String, Integer> expected = Map.of(
                "Disease-Specific (Asthma, Allergy an..., PUB)", 1,
                "Disease-Specific (Asthma, Allergy an...ation)", 1,
                "Disease-Specific (Asthma, Allergy an..., NPU)", 1,
                "Disease-Specific (Asthma, Allergy a...B, NPU)", 1
        );
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("Test Empty Map limitKeySize")
    public void testEmptyMapLimitKeySize() {
        Map<String, Integer> axisMap = new HashMap<>();
        Map<String, Integer> actual = VisualizationUtil.limitKeySize(axisMap);
        Map<String, Integer> expected = new HashMap<>();
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("Test null Map limitKeySize")
    public void testNullMapLimitKeySize() {
        Map<String, Integer> axisMap = null;
        Map<String, Integer> actual = VisualizationUtil.limitKeySize(axisMap);
        Map<String, Integer> expected = new HashMap<>();
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("Test with no long keys limitKeySize")
    public void testNoLongKeysLimitKeySize() {
        // Test with no long keys
        Map<String, Integer> axisMap = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            axisMap.put("key" + i, 1);
        }
        Map<String, Integer> actual = VisualizationUtil.limitKeySize(axisMap);
        Map<String, Integer> expected = new HashMap<>(axisMap);
        assertEquals(expected, actual);
    }

    @Test
    @DisplayName("Test with keys of greater than 45 characters and uniqueness is near middle limitKeySize")
    public void testKeysOfGreaterLengthAndUniquenessNearMiddleLimitKeySize() {
        Map<String, Integer> axisMap = new HashMap<>();
        axisMap.put("Hello, this is a long key that is STRING1 greater than 45 characters and is unique", 1);
        axisMap.put("Hello, this is a long key that is STRING2  greater than 45 characters and is unique", 1);
        axisMap.put("Hello, this is a long key that is STRING3 greater than 45 characters and is unique", 1);

        Map<String, Integer> actual = VisualizationUtil.limitKeySize(axisMap);

        // loop through the keys and check if they are less than 45 characters
        for (String key : actual.keySet()) {
            assertEquals(45, key.length());
        }

        Map<String, Integer> expected = Map.of(
                "Hello, this is a long key that is ST...unique", 1,
                "Hello, this is a long key that is S... unique", 1,
                "Hello, this is a long key that is ...s unique", 1
        );

        assertEquals(expected, actual);
    }


}
