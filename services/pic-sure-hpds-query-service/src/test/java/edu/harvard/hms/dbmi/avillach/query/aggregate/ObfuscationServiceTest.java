package edu.harvard.hms.dbmi.avillach.query.aggregate;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.query.config.AggregateProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ObfuscationServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObfuscationService svc(String salt) {
        AggregateProperties props = new AggregateProperties();
        props.getObfuscation().setThreshold(10);
        props.getObfuscation().setVariance(3);
        props.getObfuscation().setSalt(salt);
        return new ObfuscationService(props, new VisualizationFormatter());
    }

    @Test
    void thresholdFloorBelowThreshold() {
        assertThat(svc("s").applyThresholdFloor(5)).contains(new ObfuscatedCount(0, "< 10", 9));
    }

    @Test
    void thresholdFloorAtOrAboveThresholdIsEmpty() {
        assertThat(svc("s").applyThresholdFloor(10)).isEmpty();
    }

    @Test
    void randomizeUsesConfiguredVarianceInDisplayAndFloorsAtThreshold() {
        // count 100, requestVariance 0 => 100; display shows the CONFIGURED variance (3), not the request one
        assertThat(svc("s").randomize(100, 0)).isEqualTo(new ObfuscatedCount(100, "100 ±3", 3));
        // count 8, requestVariance -5 => max(3,10) floored to threshold 10
        assertThat(svc("s").randomize(8, -5)).isEqualTo(new ObfuscatedCount(10, "10 ±3", 3));
    }

    @Test
    void requestVarianceIsDeterministicAndInRange() {
        ObfuscationService s = svc("fixed");
        int v1 = s.generateRequestVariance("payload");
        int v2 = s.generateRequestVariance("payload");
        assertThat(v1).isEqualTo(v2); // deterministic
        assertThat(v1).isBetween(-3, 3); // [-variance, +variance]
    }

    @Test
    void countObfuscationFloorsSmallAndRandomizesLarge() {
        ObfuscationService s = svc("fixed");
        assertThat(s.obfuscateCount("5")).isEqualTo("< 10");
        assertThat(s.obfuscateCount("100")).matches("\\d+ ±3");
        assertThat(s.obfuscateCount("not-a-number")).isEqualTo("not-a-number"); // NFE => passthrough
    }

    @Test
    void crossCountFloorsBelowThresholdAndRandomizesAbove() throws Exception {
        String input = mapper.writeValueAsString(new LinkedHashMap<>(Map.of("\\study\\a\\", "5", "\\study\\b\\", "100")));
        Map<String, String> out = svc("fixed").processCrossCounts(input);
        assertThat(out.get("\\study\\a\\")).isEqualTo("< 10");
        assertThat(out.get("\\study\\b\\")).matches("\\d+ ±3");
    }

    @Test
    void continuousSuppressedWhenStudyConsentsBelowThresholdOrZero() {
        ObfuscationService s = svc("fixed");
        assertThat(s.canShowContinuousCrossCounts(Map.of("\\_studies_consents\\", "< 10"))).isTrue();
        assertThat(s.canShowContinuousCrossCounts(Map.of("\\_studies_consents\\", "0"))).isTrue();
        assertThat(s.canShowContinuousCrossCounts(Map.of("\\_studies_consents\\", "500"))).isFalse();
    }

    @Test
    void obfuscateCrossCountFloorsAndRandomizesNested() {
        Map<String, Map<String, Object>> nested = new LinkedHashMap<>();
        nested.put("\\axis\\", new LinkedHashMap<>(Map.of("male", 5, "female", 100)));
        var out = svc("fixed").obfuscateCrossCount(0, nested);
        assertThat(out.get("\\axis\\").get("male")).isEqualTo(new ObfuscatedCount(0, "< 10", 9));
        assertThat(out.get("\\axis\\").get("female")).isEqualTo(new ObfuscatedCount(100, "100 ±3", 3));
    }
}
