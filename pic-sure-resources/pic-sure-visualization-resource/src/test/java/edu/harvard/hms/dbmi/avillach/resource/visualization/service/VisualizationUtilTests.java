package edu.harvard.hms.dbmi.avillach.resource.visualization.service;

import edu.harvard.dbmi.avillach.util.VisualizationUtil;
import org.junit.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashMap;
import java.util.Map;

public class VisualizationUtilTests {

    @Test
    @DisplayName("Test limitKeySize")
    public void testLimitKeySizeUniqueness() {
        // Testing name uniqueness
        Map<String, Integer> axisMap = new HashMap<>();
        axisMap.put("Disease-Specific (Asthma, Allergy and Inflammation, PUB)", 1);
        axisMap.put("Disease-Specific (Asthma, Allergy and Inflammation, PUB, NPU)", 1);
        axisMap.put("Disease-Specific (Asthma, Allergy and Inflammation, NPU)", 1);
        axisMap.put("Disease-Specific (Asthma, Allergy and Inflammation)", 1);

        Map<String, Integer> stringIntegerMap = VisualizationUtil.limitKeySize(axisMap);
        assert (stringIntegerMap.size() == 4);
    }

}
