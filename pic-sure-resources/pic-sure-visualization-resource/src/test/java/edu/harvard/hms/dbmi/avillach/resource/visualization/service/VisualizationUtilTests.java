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

}
