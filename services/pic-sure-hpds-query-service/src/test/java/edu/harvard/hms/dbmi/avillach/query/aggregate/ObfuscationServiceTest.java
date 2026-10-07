package edu.harvard.hms.dbmi.avillach.query.aggregate;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.query.config.AggregateProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ObfuscationServiceTest {

    private static final String TOTAL = "\\_studies_consents\\";
    private static final String STUDY = "\\_studies_consents\\phs000001\\";
    private static final String HMB = "\\_studies_consents\\phs000001\\HMB\\";
    private static final String GRU = "\\_studies_consents\\phs000001\\GRU\\";
    private static final String DS = "\\_studies_consents\\phs000001\\DS\\";

    private final ObjectMapper mapper = new ObjectMapper();
    private final ObfuscationService svc = new ObfuscationService(new AggregateProperties(), new VisualizationFormatter());

    private Map<String, String> crossCounts(String... keysAndValues) {
        Map<String, String> counts = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            counts.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return svc.obfuscateCrossCounts(counts);
    }

    @Test
    void consentGroupBelowFiveIsSuppressedAndFiveOrMoreIsExact() {
        Map<String, String> out = crossCounts(HMB, "4", GRU, "5", DS, "0");
        assertThat(out.get(HMB)).isEqualTo("< 5");
        assertThat(out.get(GRU)).isEqualTo("5");
        assertThat(out.get(DS)).isEqualTo("< 5");
    }

    @Test
    void studyIsExactSumWhenNoConsentGroupIsSuppressed() {
        Map<String, String> out = crossCounts(STUDY, "30", HMB, "12", GRU, "18");
        assertThat(out.get(STUDY)).isEqualTo("30");
    }

    @Test
    void studyCountsEachSuppressedConsentGroupAsTwoPlusOrMinusTwo() {
        Map<String, String> out = crossCounts(STUDY, "16", HMB, "12", GRU, "3", DS, "1");
        assertThat(out.get(STUDY)).isEqualTo("16 ±4");
    }

    @Test
    void studyBandIsCappedAtTen() {
        Map<String, String> counts = new LinkedHashMap<>();
        counts.put(STUDY, "6");
        for (int i = 0; i < 6; i++) {
            counts.put(STUDY + "c" + i + "\\", "1");
        }
        assertThat(svc.obfuscateCrossCounts(counts).get(STUDY)).isEqualTo("12 ±10");
    }

    @Test
    void studyUsesSumEvenWhenHpdsStudyCountDiffers() {
        Map<String, String> out = crossCounts(STUDY, "99", HMB, "12", GRU, "18");
        assertThat(out.get(STUDY)).isEqualTo("30");
    }

    @Test
    void studyWithoutConsentGroupsIsTreatedAsConsentGroup() {
        Map<String, String> out = crossCounts("\\_studies_consents\\phs000002\\", "3", "\\_studies_consents\\phs000003\\", "40");
        assertThat(out.get("\\_studies_consents\\phs000002\\")).isEqualTo("< 5");
        assertThat(out.get("\\_studies_consents\\phs000003\\")).isEqualTo("40");
    }

    @Test
    void keysWithoutTrailingSeparatorAreRecognized() {
        Map<String, String> out = crossCounts(
            "\\_studies_consents\\phs000001", "16", "\\_studies_consents\\phs000001\\HMB", "12", "\\_studies_consents\\phs000001\\GRU", "3"
        );
        assertThat(out.get("\\_studies_consents\\phs000001")).isEqualTo("14 ±2");
    }

    @Test
    void totalIsRoundedUpToFiveWithBandOfTen() {
        assertThat(crossCounts(TOTAL, "101").get(TOTAL)).isEqualTo("105 ±10");
        assertThat(crossCounts(TOTAL, "100").get(TOTAL)).isEqualTo("100 ±10");
        assertThat(crossCounts(TOTAL, "5").get(TOTAL)).isEqualTo("5 ±10");
        assertThat(crossCounts(TOTAL, "6").get(TOTAL)).isEqualTo("10 ±10");
    }

    @Test
    void totalBelowFiveIsSuppressed() {
        assertThat(crossCounts(TOTAL, "4").get(TOTAL)).isEqualTo("< 5");
        assertThat(crossCounts(TOTAL, "0").get(TOTAL)).isEqualTo("< 5");
    }

    @Test
    void unparseableCountsAreSuppressed() {
        Map<String, String> out = crossCounts(TOTAL, "oops", STUDY, "20", HMB, "x", GRU, "18");
        assertThat(out.get(TOTAL)).isEqualTo("< 5");
        assertThat(out.get(HMB)).isEqualTo("< 5");
        assertThat(out.get(STUDY)).isEqualTo("20 ±2");
    }

    @Test
    void processCrossCountsKeepsKeyOrder() throws Exception {
        Map<String, String> in = new LinkedHashMap<>();
        in.put(TOTAL, "100");
        in.put(STUDY, "30");
        in.put(HMB, "12");
        in.put(GRU, "18");
        Map<String, String> out = svc.processCrossCounts(mapper.writeValueAsString(in));
        assertThat(out.keySet()).containsExactly(TOTAL, STUDY, HMB, GRU);
    }

    @Test
    void chartSuppressedBelowFiftyAndShownAtFifty() {
        assertThat(svc.shouldSuppressChart(Map.of(TOTAL, "49"))).isTrue();
        assertThat(svc.shouldSuppressChart(Map.of(TOTAL, "50"))).isFalse();
    }

    @Test
    void chartSuppressionFailsClosedOnMissingOrUnparseableTotal() {
        assertThat(svc.shouldSuppressChart(Map.of())).isTrue();
        assertThat(svc.shouldSuppressChart(null)).isTrue();
        assertThat(svc.shouldSuppressChart(Map.of(TOTAL, "not-a-number"))).isTrue();
    }

    @Test
    void chartBucketBelowTenIsSuppressed() {
        assertThat(svc.obfuscateChartBucket(9)).isEqualTo(new ObfuscatedCount(0, "< 10", 9));
        assertThat(svc.obfuscateChartBucket(0)).isEqualTo(new ObfuscatedCount(0, "< 10", 9));
    }

    @Test
    void chartBucketIsRoundedUpToFiveWithBandOfFive() {
        assertThat(svc.obfuscateChartBucket(10)).isEqualTo(new ObfuscatedCount(10, "10 ±5", 5));
        assertThat(svc.obfuscateChartBucket(11)).isEqualTo(new ObfuscatedCount(15, "15 ±5", 5));
        assertThat(svc.obfuscateChartBucket(100)).isEqualTo(new ObfuscatedCount(100, "100 ±5", 5));
    }

    @Test
    void obfuscateChartCountsAppliesBucketRulesToNestedMaps() {
        Map<String, Map<String, Object>> nested = new LinkedHashMap<>();
        nested.put("\\axis\\", new LinkedHashMap<>(Map.of("male", 5, "female", 101)));
        var out = svc.obfuscateChartCounts(nested);
        assertThat(out.get("\\axis\\").get("male")).isEqualTo(new ObfuscatedCount(0, "< 10", 9));
        assertThat(out.get("\\axis\\").get("female")).isEqualTo(new ObfuscatedCount(105, "105 ±5", 5));
    }

    @Test
    void categoricalReturnsNullForSmallCohort() throws Exception {
        String categorical = "{\"\\\\gender\\\\\":{\"male\":20,\"female\":20}}";
        assertThat(svc.processCategoricalCrossCounts(categorical, "{\"\\\\_studies_consents\\\\\":\"40\"}")).isNull();
        assertThat(svc.processCategoricalCrossCounts(categorical, "{\"\\\\_studies_consents\\\\\":\"50\"}")).contains("20 ±5");
    }

    @Test
    void countReturnsOnlyTheObfuscatedTotal() throws Exception {
        assertThat(svc.processCount("{\"\\\\_studies_consents\\\\\":\"101\",\"\\\\_studies_consents\\\\phs1\\\\\":\"3\"}"))
            .isEqualTo("105 ±10");
        assertThat(svc.processCount("{\"\\\\_studies_consents\\\\\":\"4\"}")).isEqualTo("< 5");
        assertThat(svc.processCount("{}")).isEqualTo("< 5");
    }

    @Test
    void allValuesScaleWithTheBaseThreshold() {
        AggregateProperties props = new AggregateProperties();
        props.getObfuscation().setConsentThreshold(10);
        ObfuscationService scaled = new ObfuscationService(props, new VisualizationFormatter());

        // Suppressed consent group: 4 ±5; study and total band 5 x 5 = 25
        Map<String, String> study = new LinkedHashMap<>();
        study.put(STUDY, "20");
        study.put(HMB, "20");
        study.put(GRU, "9");
        assertThat(scaled.obfuscateCrossCounts(study).get(STUDY)).isEqualTo("24 ±5");
        assertThat(scaled.obfuscateCrossCounts(new LinkedHashMap<>(Map.of(TOTAL, "101"))).get(TOTAL)).isEqualTo("110 ±25");
        assertThat(scaled.obfuscateCrossCounts(new LinkedHashMap<>(Map.of(TOTAL, "9"))).get(TOTAL)).isEqualTo("< 10");

        // Charts: threshold 20, rounding and band 10; the minimum cohort does not scale
        assertThat(scaled.obfuscateChartBucket(19)).isEqualTo(new ObfuscatedCount(0, "< 20", 19));
        assertThat(scaled.obfuscateChartBucket(21)).isEqualTo(new ObfuscatedCount(30, "30 ±10", 10));
        assertThat(scaled.shouldSuppressChart(Map.of(TOTAL, "49"))).isTrue();
        assertThat(scaled.shouldSuppressChart(Map.of(TOTAL, "50"))).isFalse();
    }

    @Test
    void chartMinimumCohortIsConfiguredSeparately() {
        AggregateProperties props = new AggregateProperties();
        props.getObfuscation().setChartMinimumCohort(200);
        ObfuscationService custom = new ObfuscationService(props, new VisualizationFormatter());
        assertThat(custom.shouldSuppressChart(Map.of(TOTAL, "199"))).isTrue();
        assertThat(custom.shouldSuppressChart(Map.of(TOTAL, "200"))).isFalse();
        assertThat(custom.obfuscateChartBucket(9)).isEqualTo(new ObfuscatedCount(0, "< 10", 9));
    }
}
