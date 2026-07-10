package edu.harvard.hms.dbmi.avillach.query.aggregate;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VisualizationFormatterTest {

    private final VisualizationFormatter fmt = new VisualizationFormatter();

    @Test
    void skipsConsentAxisKeys() {
        assertThat(fmt.skipKey("\\_consents\\")).isTrue();
        assertThat(fmt.skipKey("\\_harmonized_consent\\")).isTrue();
        assertThat(fmt.skipKey("\\_topmed_consents\\")).isTrue();
        assertThat(fmt.skipKey("\\_parent_consents\\")).isTrue();
        assertThat(fmt.skipKey("\\demographics\\SEX\\")).isFalse();
    }

    @Test
    void collapsesBeyondSevenIntoOther() {
        Map<String, Object> axis = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) axis.put("cat" + i, i + 1); // 10 categories, all Integer
        Map<String, Object> out = fmt.processResults(axis);
        assertThat(out).containsKey("Other");
        assertThat(out.size()).isEqualTo(8); // top 7 + Other
    }

    @Test
    void keepsSmallMapsIntact() {
        Map<String, Object> axis = new LinkedHashMap<>(Map.of("a", 1, "b", 2));
        Map<String, Object> out = fmt.processResults(axis);
        assertThat(out).containsOnlyKeys("a", "b");
    }
}
