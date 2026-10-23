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
    // Most tests verify the per-study and per-consent obfuscation, so they keep the full breakdown in the output.
    private final ObfuscationService svc = new ObfuscationService(fullBreakdownProps(), new VisualizationFormatter());

    private static AggregateProperties fullBreakdownProps() {
        AggregateProperties props = new AggregateProperties();
        props.getObfuscation().setCrossCountTotalOnly(false);
        return props;
    }

    private Map<String, String> crossCounts(String... keysAndValues) {
        Map<String, String> counts = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            counts.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return svc.obfuscateCrossCounts(counts);
    }

    @Test
    void consentGroupBelowTenIsSuppressed() {
        Map<String, String> out = crossCounts(HMB, "9", GRU, "5", DS, "0");
        assertThat(out.get(HMB)).isEqualTo("< 10");
        assertThat(out.get(GRU)).isEqualTo("< 10");
        assertThat(out.get(DS)).isEqualTo("< 10");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"10, 15 ±5", "12, 15 ±5", "19, 15 ±5", "20, 25 ±5", "40, 45 ±5", "1234, 1235 ±5"})
    void consentGroupShowsTheMidpointOfItsTenWideBucket(String count, String expected) {
        assertThat(crossCounts(HMB, count).get(HMB)).isEqualTo(expected);
    }

    @Test
    void studyIsBucketedEvenWhenNoConsentGroupIsSuppressed() {
        assertThat(crossCounts(STUDY, "40", HMB, "17", GRU, "23").get(STUDY)).isEqualTo("45 ±5");
        assertThat(crossCounts(STUDY, "30", HMB, "12", GRU, "18").get(STUDY)).isEqualTo("35 ±5");
    }

    @Test
    void studyCountsEachSuppressedConsentGroupAsFivePlusOrMinusFive() {
        // 12 + 5 = 17
        assertThat(crossCounts(STUDY, "15", HMB, "12", GRU, "3").get(STUDY)).isEqualTo("15 ±10");
        // 12 + 5 + 5 = 22
        assertThat(crossCounts(STUDY, "16", HMB, "12", GRU, "3", DS, "1").get(STUDY)).isEqualTo("25 ±15");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1, < 10", "2, 15 ±15", "5, 25 ±30", "8, 45 ±45", "50, 255 ±255"})
    void studyBandGrowsWithEverySuppressedConsentGroupWithoutACap(int suppressed, String expected) {
        Map<String, String> counts = new LinkedHashMap<>();
        counts.put(STUDY, String.valueOf(suppressed));
        for (int i = 0; i < suppressed; i++) {
            counts.put(STUDY + "c" + i + "\\", "1");
        }
        assertThat(svc.obfuscateCrossCounts(counts).get(STUDY)).isEqualTo(expected);
    }

    @Test
    void studyBandIsNotCappedWhenNamedAndHiddenGroupsAreMixed() {
        // 40 named + 8 hidden at 5 each = 80, with a band of 5 + 5 x 8 = 45
        Map<String, String> counts = new LinkedHashMap<>();
        counts.put(STUDY, "52");
        counts.put(HMB, "40");
        for (int i = 0; i < 8; i++) {
            counts.put(STUDY + "c" + i + "\\", "1");
        }
        Map<String, String> out = svc.obfuscateCrossCounts(counts);
        assertThat(out.get(STUDY)).isEqualTo("85 ±45");
        assertThat(out.get(HMB)).isEqualTo("45 ±5");
    }

    @Test
    void studySumBelowTenIsSuppressed() {
        // A suppressed group alone adds 5, below the threshold; the true count of 9 is not revealed either.
        assertThat(crossCounts(STUDY, "9", HMB, "9").get(STUDY)).isEqualTo("< 10");
    }

    @Test
    void studyUsesSumEvenWhenHpdsStudyCountDiffers() {
        Map<String, String> out = crossCounts(STUDY, "99", HMB, "12", GRU, "18");
        assertThat(out.get(STUDY)).isEqualTo("35 ±5");
    }

    @Test
    void studyWithoutConsentGroupsIsTreatedAsConsentGroup() {
        Map<String, String> out = crossCounts("\\_studies_consents\\phs000002\\", "9", "\\_studies_consents\\phs000003\\", "40");
        assertThat(out.get("\\_studies_consents\\phs000002\\")).isEqualTo("< 10");
        assertThat(out.get("\\_studies_consents\\phs000003\\")).isEqualTo("45 ±5");
    }

    @Test
    void keysWithoutTrailingSeparatorAreRecognized() {
        Map<String, String> out = crossCounts(
            "\\_studies_consents\\phs000001", "15", "\\_studies_consents\\phs000001\\HMB", "12", "\\_studies_consents\\phs000001\\GRU", "3"
        );
        assertThat(out.get("\\_studies_consents\\phs000001")).isEqualTo("15 ±10");
    }

    @Test
    void totalIsRoundedUpToFiveWithBandOfTen() {
        assertThat(crossCounts(TOTAL, "101").get(TOTAL)).isEqualTo("105 ±10");
        assertThat(crossCounts(TOTAL, "100").get(TOTAL)).isEqualTo("100 ±10");
        assertThat(crossCounts(TOTAL, "10").get(TOTAL)).isEqualTo("10 ±10");
        assertThat(crossCounts(TOTAL, "11").get(TOTAL)).isEqualTo("15 ±10");
    }

    @Test
    void totalBelowTenIsSuppressed() {
        assertThat(crossCounts(TOTAL, "9").get(TOTAL)).isEqualTo("< 10");
        assertThat(crossCounts(TOTAL, "5").get(TOTAL)).isEqualTo("< 10");
        assertThat(crossCounts(TOTAL, "0").get(TOTAL)).isEqualTo("< 10");
    }

    @Test
    void unparseableCountsAreSuppressed() {
        // The unparseable group adds 5 to its study: 5 + 18 = 23
        Map<String, String> out = crossCounts(TOTAL, "oops", STUDY, "20", HMB, "x", GRU, "18");
        assertThat(out.get(TOTAL)).isEqualTo("< 10");
        assertThat(out.get(HMB)).isEqualTo("< 10");
        assertThat(out.get(STUDY)).isEqualTo("25 ±10");
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
    void crossCountTotalOnlyByDefaultReturnsOnlyTheTotal() {
        // The breakdown is still computed, but only the total reaches the client.
        ObfuscationService totalOnly = new ObfuscationService(new AggregateProperties(), new VisualizationFormatter());
        Map<String, String> in = new LinkedHashMap<>();
        in.put(TOTAL, "101");
        in.put(STUDY, "30");
        in.put(HMB, "12");
        in.put(GRU, "18");
        Map<String, String> out = totalOnly.obfuscateCrossCounts(in);
        assertThat(out).containsOnlyKeys(TOTAL);
        assertThat(out.get(TOTAL)).isEqualTo("105 ±10");
    }

    @Test
    void crossCountTotalOnlyOffReturnsEveryEntry() {
        Map<String, String> out = crossCounts(TOTAL, "101", STUDY, "30", HMB, "12", GRU, "18");
        assertThat(out).containsKeys(TOTAL, STUDY, HMB, GRU);
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
        assertThat(svc.processCategoricalCrossCounts(categorical, "{\"\\\\_studies_consents\\\\\":\"40\"}", Map.of())).isNull();
        assertThat(svc.processCategoricalCrossCounts(categorical, "{\"\\\\_studies_consents\\\\\":\"50\"}", Map.of())).contains("20 ±5");
    }

    @Test
    void countReturnsOnlyTheObfuscatedTotal() throws Exception {
        assertThat(svc.processCount("{\"\\\\_studies_consents\\\\\":\"101\",\"\\\\_studies_consents\\\\phs1\\\\\":\"3\"}"))
            .isEqualTo("105 ±10");
        assertThat(svc.processCount("{\"\\\\_studies_consents\\\\\":\"4\"}")).isEqualTo("< 10");
        assertThat(svc.processCount("{\"\\\\_studies_consents\\\\\":\"9\"}")).isEqualTo("< 10");
        assertThat(svc.processCount("{}")).isEqualTo("< 10");
    }

    @Test
    void chartThresholdIsConfiguredSeparately() {
        AggregateProperties props = new AggregateProperties();
        props.getObfuscation().setChartThreshold(20);
        props.getObfuscation().setCrossCountTotalOnly(false);
        ObfuscationService custom = new ObfuscationService(props, new VisualizationFormatter());

        // Charts: hidden below 20, rounded up to 10 with ±10; the minimum cohort does not change
        assertThat(custom.obfuscateChartBucket(19)).isEqualTo(new ObfuscatedCount(0, "< 20", 19));
        assertThat(custom.obfuscateChartBucket(21)).isEqualTo(new ObfuscatedCount(30, "30 ±10", 10));
        assertThat(custom.shouldSuppressChart(Map.of(TOTAL, "49"))).isTrue();
        assertThat(custom.shouldSuppressChart(Map.of(TOTAL, "50"))).isFalse();
        // Consent groups and the total keep the consent threshold
        assertThat(custom.obfuscateCrossCounts(new LinkedHashMap<>(Map.of(HMB, "12"))).get(HMB)).isEqualTo("15 ±5");
        assertThat(custom.obfuscateCrossCounts(new LinkedHashMap<>(Map.of(TOTAL, "101"))).get(TOTAL)).isEqualTo("105 ±10");
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

    @Test
    void maxChartBinsScalesWithTheDisplayedCohortTotal() {
        assertThat(svc.maxChartBins(Map.of(TOTAL, "50"))).isEqualTo(5);
        // 51 displays as 55, so it still gets 5 bins rather than revealing more than the displayed total
        assertThat(svc.maxChartBins(Map.of(TOTAL, "51"))).isEqualTo(5);
        assertThat(svc.maxChartBins(Map.of(TOTAL, "56"))).isEqualTo(6);
        assertThat(svc.maxChartBins(Map.of(TOTAL, "200"))).isEqualTo(20);
        assertThat(svc.maxChartBins(Map.of(TOTAL, "100000"))).isEqualTo(50);
        assertThat(svc.maxChartBins(Map.of())).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {-1, 0, 1, 4, 5, 9})
    void consentThresholdsBelowTenBehaveAsTen(int configured) {
        AggregateProperties props = new AggregateProperties();
        props.getObfuscation().setConsentThreshold(configured);
        props.getObfuscation().setCrossCountTotalOnly(false);
        ObfuscationService clamped = new ObfuscationService(props, new VisualizationFormatter());

        Map<String, String> study = new LinkedHashMap<>();
        study.put(STUDY, "16");
        study.put(HMB, "12");
        study.put(GRU, "3");
        study.put(TOTAL, "101");
        assertThat(clamped.obfuscateCrossCounts(study)).isEqualTo(svc.obfuscateCrossCounts(study));
        assertThat(clamped.obfuscateChartBucket(9)).isEqualTo(new ObfuscatedCount(0, "< 10", 9));
        assertThat(clamped.maxChartBins(Map.of(TOTAL, "200"))).isEqualTo(20);
    }

    @Test
    void consentThresholdsAboveTenAreKept() {
        AggregateProperties props = new AggregateProperties();
        props.getObfuscation().setConsentThreshold(20);
        props.getObfuscation().setCrossCountTotalOnly(false);
        ObfuscationService raised = new ObfuscationService(props, new VisualizationFormatter());

        // 20-wide buckets shown at the midpoint ±10; a suppressed group adds 10 ±10; the total rounds up to 10 with ±20
        assertThat(raised.obfuscateCrossCounts(new LinkedHashMap<>(Map.of(HMB, "19"))).get(HMB)).isEqualTo("< 20");
        assertThat(raised.obfuscateCrossCounts(new LinkedHashMap<>(Map.of(HMB, "25"))).get(HMB)).isEqualTo("30 ±10");
        Map<String, String> study = new LinkedHashMap<>();
        study.put(STUDY, "33");
        study.put(HMB, "30");
        study.put(GRU, "3");
        assertThat(raised.obfuscateCrossCounts(study).get(STUDY)).isEqualTo("50 ±20");
        assertThat(raised.obfuscateCrossCounts(new LinkedHashMap<>(Map.of(TOTAL, "101"))).get(TOTAL)).isEqualTo("110 ±20");
        // Charts keep their own threshold
        assertThat(raised.obfuscateChartBucket(10)).isEqualTo(new ObfuscatedCount(10, "10 ±5", 5));
    }
}
