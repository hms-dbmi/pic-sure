package edu.harvard.hms.dbmi.avillach.query.aggregate;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.query.config.AggregateProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises obfuscation at a count threshold of 5, which is what deployed environments are configured for. Chart thresholds are left to
 * derive from it (2x, so 10) except where a test is specifically about overriding them.
 */
class ObfuscationServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Count threshold 5, chart thresholds derived (10), variance effectively uncapped. */
    private ObfuscationService svc() {
        return svc(5, null, null, Integer.MAX_VALUE);
    }

    /** Obfuscation turned off; chart thresholds derive to 0 too. */
    private ObfuscationService disabled() {
        return svc(0, null, null, Integer.MAX_VALUE);
    }

    /** Count threshold 5 with an explicit variance cap. */
    private ObfuscationService cappedSvc(int maxVariance) {
        return svc(5, null, null, maxVariance);
    }

    private ObfuscationService svc(int threshold, Integer categoricalThreshold, Integer continuousThreshold, int maxVariance) {
        AggregateProperties props = new AggregateProperties();
        props.getObfuscation().setThreshold(threshold);
        props.getObfuscation().setCategoricalThreshold(categoricalThreshold);
        props.getObfuscation().setContinuousThreshold(continuousThreshold);
        props.getObfuscation().setMaxVariance(maxVariance);
        return new ObfuscationService(props, new VisualizationFormatter());
    }

    private Map<String, String> crossCounts(ObfuscationService s, String... keysAndValues) throws Exception {
        Map<String, String> input = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            input.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return s.processCrossCounts(mapper.writeValueAsString(input));
    }

    private Map<String, String> crossCounts(String... keysAndValues) throws Exception {
        return crossCounts(svc(), keysAndValues);
    }

    private static Map<String, Map<String, Object>> buckets(Object... labelsAndCounts) {
        Map<String, Object> inner = new LinkedHashMap<>();
        for (int i = 0; i < labelsAndCounts.length; i += 2) {
            inner.put((String) labelsAndCounts[i], labelsAndCounts[i + 1]);
        }
        Map<String, Map<String, Object>> nested = new LinkedHashMap<>();
        nested.put("\\axis\\", inner);
        return nested;
    }

    // ---- threshold floor ----

    @Test
    void thresholdFloorBelowThreshold() {
        // a hidden value is somewhere in 0..4, so the exact band around the published 0 is 4
        assertThat(svc().applyThresholdFloor(3)).contains(new ObfuscatedCount(0, "< 5", 4));
    }

    @Test
    void thresholdFloorAtOrAboveThresholdIsEmpty() {
        assertThat(svc().applyThresholdFloor(5)).isEmpty(); // at the threshold is NOT below it
        assertThat(svc().applyThresholdFloor(999)).isEmpty();
    }

    @Test
    void countObfuscationFloorsSmallAndLeavesLargeExact() {
        ObfuscationService s = svc();
        assertThat(s.obfuscateCount("3")).isEqualTo("< 5");
        assertThat(s.obfuscateCount("4")).isEqualTo("< 5");
        assertThat(s.obfuscateCount("5")).isEqualTo("5"); // at threshold => exact
        assertThat(s.obfuscateCount("100")).isEqualTo("100"); // above threshold => exact, never perturbed
        assertThat(s.obfuscateCount("not-a-number")).isEqualTo("not-a-number"); // NFE => passthrough
    }

    // ---- CROSS_COUNT leaves ----

    @Test
    void crossCountLeavesAreSuppressedBelowThresholdAndExactAbove() throws Exception {
        Map<String, String> out = crossCounts("\\study\\a\\", "3", "\\study\\b\\", "100");
        assertThat(out.get("\\study\\a\\")).isEqualTo("< 5");
        assertThat(out.get("\\study\\b\\")).isEqualTo("100");
    }

    @Test
    void crossCountPassesNonNumericThrough() throws Exception {
        assertThat(crossCounts("\\study\\a\\", "not-a-number").get("\\study\\a\\")).isEqualTo("not-a-number");
    }

    // ---- CROSS_COUNT aggregates ----

    /** The worked example: each suppressed child contributes threshold-1 of variance, and the parent rounds up to a multiple of 5. */
    @Test
    void aggregatePathRoundsItsOwnCountAndInheritsChildVariance() throws Exception {
        Map<String, String> out = crossCounts("\\study\\", "508", "\\study\\a\\", "3", "\\study\\b\\", "2", "\\study\\c\\", "500");

        assertThat(out.get("\\study\\a\\")).isEqualTo("< 5");
        assertThat(out.get("\\study\\b\\")).isEqualTo("< 5");
        assertThat(out.get("\\study\\c\\")).isEqualTo("500");
        // 508 rounds up to 510; variance 4 + 4 = 8
        assertThat(out.get("\\study\\")).isEqualTo("510 ±8");
    }

    @Test
    void aggregateCountAlreadyOnTheRoundingStepIsNotMovedFurther() throws Exception {
        Map<String, String> out = crossCounts("\\study\\", "500", "\\study\\a\\", "3", "\\study\\b\\", "500");
        assertThat(out.get("\\study\\")).isEqualTo("500 ±4");
    }

    @Test
    void aggregateWithNonNumericCountPassesThrough() throws Exception {
        Map<String, String> out = crossCounts("\\study\\", "n/a", "\\study\\a\\", "500");
        assertThat(out.get("\\study\\")).isEqualTo("n/a");
        assertThat(out.get("\\study\\a\\")).isEqualTo("500");
    }

    @Test
    void aggregateWithNoSuppressedChildrenIsExactAndUnrounded() throws Exception {
        // 601 is not a multiple of the threshold; with nothing hidden there is nothing to round away from and no band to add
        assertThat(crossCounts("\\study\\", "601", "\\study\\a\\", "100", "\\study\\b\\", "500").get("\\study\\")).isEqualTo("601");
        assertThat(crossCounts("\\study\\", "600", "\\study\\a\\", "100", "\\study\\b\\", "500").get("\\study\\")).isEqualTo("600");
    }

    @Test
    void roundingAndBandingBeginOnlyOnceADescendantIsSuppressed() throws Exception {
        // identical parent count; the one suppressed child is what triggers both
        assertThat(crossCounts("\\s\\", "603", "\\s\\a\\", "600").get("\\s\\")).isEqualTo("603");
        assertThat(crossCounts("\\s\\", "603", "\\s\\a\\", "600", "\\s\\b\\", "2").get("\\s\\")).isEqualTo("605 ±4");
    }

    @Test
    void aSuppressedDescendantRoundsUpToTheNextThresholdMultiple() throws Exception {
        // the step is a whole threshold, and always upward; an exact multiple is left alone
        assertThat(crossCounts("\\s\\", "600", "\\s\\a\\", "600", "\\s\\b\\", "2").get("\\s\\")).isEqualTo("600 ±4");
        assertThat(crossCounts("\\s\\", "601", "\\s\\a\\", "600", "\\s\\b\\", "2").get("\\s\\")).isEqualTo("605 ±4");
        assertThat(crossCounts("\\s\\", "604", "\\s\\a\\", "600", "\\s\\b\\", "2").get("\\s\\")).isEqualTo("605 ±4");
    }

    @Test
    void aTreeWithNoSuppressionStaysExactAtEveryLevel() throws Exception {
        Map<String, String> out =
            crossCounts("\\s\\", "900", "\\s\\a\\", "400", "\\s\\a\\x\\", "400", "\\s\\b\\", "500", "\\s\\b\\y\\", "500");
        assertThat(out.get("\\s\\a\\")).isEqualTo("400");
        assertThat(out.get("\\s\\b\\")).isEqualTo("500");
        assertThat(out.get("\\s\\")).isEqualTo("900");
    }

    @Test
    void aggregateKeepsItsOwnCountRatherThanSummingChildren() throws Exception {
        // children are summed by HPDS, not by us: a parent that overlaps its children keeps its own total, not 600
        Map<String, String> out = crossCounts("\\study\\", "999", "\\study\\a\\", "100", "\\study\\b\\", "500");
        assertThat(out.get("\\study\\")).isEqualTo("999");
    }

    @Test
    void varianceAccumulatesAcrossMultipleLevels() throws Exception {
        Map<String, String> out =
            crossCounts("\\study\\", "508", "\\study\\a\\", "505", "\\study\\a\\x\\", "2", "\\study\\a\\y\\", "500", "\\study\\b\\", "3");

        assertThat(out.get("\\study\\a\\x\\")).isEqualTo("< 5");
        assertThat(out.get("\\study\\a\\y\\")).isEqualTo("500");
        // \study\a\ own count 505 is already a multiple of 5; variance 4 from the suppressed x
        assertThat(out.get("\\study\\a\\")).isEqualTo("505 ±4");
        // \study\ own count 508 rounds up to 510; variance 4 (inherited via a) + 4 (suppressed b) = 8
        assertThat(out.get("\\study\\")).isEqualTo("510 ±8");
    }

    @Test
    void aggregateBelowThresholdIsItselfSuppressed() throws Exception {
        Map<String, String> out = crossCounts("\\study\\", "3", "\\study\\a\\", "2", "\\study\\b\\", "1");
        assertThat(out.get("\\study\\a\\")).isEqualTo("< 5");
        assertThat(out.get("\\study\\b\\")).isEqualTo("< 5");
        assertThat(out.get("\\study\\")).isEqualTo("< 5");
    }

    // ---- path hierarchy ----

    /** A parent need not be separator-terminated: real consent paths appear both with and without a trailing backslash. */
    @Test
    void parentWithoutATrailingSeparatorStillParentsItsChild() throws Exception {
        Map<String, String> out = crossCounts(
            "\\_studies_consents\\phs001194", "604", "\\_studies_consents\\phs001194\\HMB", "600", "\\_studies_consents\\phs001194\\GRU",
            "2"
        );

        assertThat(out.get("\\_studies_consents\\phs001194\\HMB")).isEqualTo("600");
        assertThat(out.get("\\_studies_consents\\phs001194\\GRU")).isEqualTo("< 5");
        // the GRU child is suppressed, so the parent rounds and bands -- proving it saw both children
        assertThat(out.get("\\_studies_consents\\phs001194")).isEqualTo("605 ±4");
    }

    @Test
    void aPathIsNotTheParentOfASiblingThatMerelyExtendsItsLastSegment() throws Exception {
        // \...\HMB must NOT claim \...\HMB_2: the child does not continue at a segment boundary
        Map<String, String> out = crossCounts("\\_studies_consents\\phs000007\\HMB", "500", "\\_studies_consents\\phs000007\\HMB_2", "2");

        // both are leaves, so the big one stays exact rather than becoming a spurious banded aggregate
        assertThat(out.get("\\_studies_consents\\phs000007\\HMB")).isEqualTo("500");
        assertThat(out.get("\\_studies_consents\\phs000007\\HMB_2")).isEqualTo("< 5");
    }

    @Test
    void aPathWithoutATrailingSeparatorStillParentsADeeperSegment() throws Exception {
        Map<String, String> out =
            crossCounts("\\_studies_consents\\phs000007\\HMB", "500", "\\_studies_consents\\phs000007\\HMB\\sub\\", "2");
        assertThat(out.get("\\_studies_consents\\phs000007\\HMB")).isEqualTo("500 ±4");
    }

    @Test
    void siblingPathsThatMerelyShareAPrefixAreNotTreatedAsParentAndChild() throws Exception {
        // \stud\ is a string prefix of neither \study\a\ nor \study\b\ once the trailing separator is included
        Map<String, String> out = crossCounts("\\stud\\", "500", "\\study\\a\\", "100");
        assertThat(out.get("\\stud\\")).isEqualTo("500");
        assertThat(out.get("\\study\\a\\")).isEqualTo("100");
    }

    // ---- variance cap ----

    @Test
    void accumulatedVarianceIsCappedAtMaxVariance() throws Exception {
        // 6 suppressed children would accumulate 6 x 4 = 24; the cap holds the published band at 20
        Map<String, String> out = crossCounts(
            cappedSvc(20), "\\s\\", "500", "\\s\\a\\", "1", "\\s\\b\\", "2", "\\s\\c\\", "3", "\\s\\d\\", "4", "\\s\\e\\", "1", "\\s\\f\\",
            "2"
        );
        assertThat(out.get("\\s\\")).isEqualTo("500 ±20");
    }

    @Test
    void varianceBelowTheCapIsLeftAlone() throws Exception {
        Map<String, String> out = crossCounts(cappedSvc(25), "\\s\\", "500", "\\s\\a\\", "1", "\\s\\b\\", "2");
        assertThat(out.get("\\s\\")).isEqualTo("500 ±8");
    }

    /** The motivating case: every consent path is a child of the injected study-consents prefix. */
    @Test
    void studyConsentsRollupStaysUsableWithManySmallConsentGroups() throws Exception {
        List<String> kv = new ArrayList<>(List.of("\\_studies_consents\\", "1000"));
        for (int i = 0; i < 250; i++) {
            kv.add("\\_studies_consents\\phs" + i + "\\HMB\\");
            kv.add("2");
        }
        Map<String, String> out = crossCounts(cappedSvc(50), kv.toArray(new String[0]));
        // uncapped this would be 250 * 4 = 1000
        assertThat(out.get("\\_studies_consents\\")).isEqualTo("1000 ±50");
    }

    @Test
    void theCapBoundsAncestorsToo() throws Exception {
        Map<String, String> out = crossCounts(
            cappedSvc(15), "\\s\\", "900", "\\s\\a\\", "400", "\\s\\a\\x\\", "1", "\\s\\a\\y\\", "2", "\\s\\a\\z\\", "3", "\\s\\a\\w\\",
            "4", "\\s\\b\\", "1", "\\s\\c\\", "2"
        );
        assertThat(out.get("\\s\\a\\")).isEqualTo("400 ±15");
        assertThat(out.get("\\s\\")).isEqualTo("900 ±15");
    }

    // ---- continuous suppression gate ----

    @Test
    void continuousSuppressedWhenStudyConsentsBelowThresholdOrZero() {
        ObfuscationService s = svc();
        assertThat(s.shouldSuppressContinuousCrossCounts(Map.of("\\_studies_consents\\", "< 5"))).isTrue();
        assertThat(s.shouldSuppressContinuousCrossCounts(Map.of("\\_studies_consents\\", "0"))).isTrue();
        assertThat(s.shouldSuppressContinuousCrossCounts(Map.of("\\_studies_consents\\", "500"))).isFalse();
    }

    @Test
    void continuousSuppressedForRawBelowThresholdCounts() {
        // the caller passes the RAW backend cross-count, so plain numerics 1..threshold-1 must suppress too
        ObfuscationService s = svc();
        assertThat(s.shouldSuppressContinuousCrossCounts(Map.of("\\_studies_consents\\", "3"))).isTrue();
        assertThat(s.shouldSuppressContinuousCrossCounts(Map.of("\\_studies_consents\\", "4"))).isTrue();
        assertThat(s.shouldSuppressContinuousCrossCounts(Map.of("\\_studies_consents\\", "5"))).isFalse();
    }

    @Test
    void continuousSuppressionFailsClosedOnMissingOrUnparseableCount() {
        ObfuscationService s = svc();
        assertThat(s.shouldSuppressContinuousCrossCounts(Map.of())).isTrue();
        assertThat(s.shouldSuppressContinuousCrossCounts(null)).isTrue();
        assertThat(s.shouldSuppressContinuousCrossCounts(Map.of("\\_studies_consents\\", "not-a-number"))).isTrue();
    }

    // ---- threshold 0 disables obfuscation ----

    /** A threshold of 0 is a supported kill switch, not a misconfiguration. */
    @Test
    void thresholdOfZeroLeavesCountsExact() throws Exception {
        ObfuscationService s = disabled();

        assertThat(s.applyThresholdFloor(0)).isEmpty();
        assertThat(s.applyThresholdFloor(1)).isEmpty();
        assertThat(s.obfuscateCount("1")).isEqualTo("1");
        assertThat(crossCounts(s, "\\study\\a\\", "1", "\\study\\b\\", "100").get("\\study\\a\\")).isEqualTo("1");
    }

    @Test
    void thresholdOfZeroLeavesAggregatesExactAndUnrounded() throws Exception {
        Map<String, String> out = crossCounts(disabled(), "\\study\\", "601", "\\study\\a\\", "1", "\\study\\b\\", "600");

        assertThat(out.get("\\study\\a\\")).isEqualTo("1");
        assertThat(out.get("\\study\\")).isEqualTo("601"); // no suppression means nothing to round or band
    }

    @Test
    void thresholdOfZeroStopsTheContinuousGateFromSuppressing() {
        ObfuscationService s = disabled();

        assertThat(s.shouldSuppressContinuousCrossCounts(Map.of("\\_studies_consents\\", "1"))).isFalse();
        assertThat(s.shouldSuppressContinuousCrossCounts(Map.of("\\_studies_consents\\", "0"))).isFalse();
        // even the fail-closed paths open up: with obfuscation off there is nothing to withhold
        assertThat(s.shouldSuppressContinuousCrossCounts(Map.of())).isFalse();
        assertThat(s.shouldSuppressContinuousCrossCounts(null)).isFalse();
    }

    @Test
    void thresholdOfZeroLeavesChartBucketsExactRatherThanBandingAtZero() {
        // the derived chart threshold is 2 x 0 = 0, so buckets pass through with a null (exact) variance
        var out = disabled().obfuscateCategoricalCrossCount(buckets("rare", 1, "common", 103));

        assertThat(out.get("\\axis\\").get("rare")).isEqualTo(ObfuscatedCount.ofInt(1));
        assertThat(out.get("\\axis\\").get("common")).isEqualTo(ObfuscatedCount.ofInt(103));
    }

    // ---- chart buckets ----

    /** With a count threshold of 5 the chart threshold derives to 10, so buckets are 10 wide and band at 5. */
    @Test
    void chartBucketsUseTheDerivedThresholdOfTwiceTheCountThreshold() {
        var out = svc().obfuscateCategoricalCrossCount(buckets("rare", 9, "lo", 10, "mid", 14, "hi", 19, "next", 20));

        assertThat(out.get("\\axis\\").get("rare")).isEqualTo(new ObfuscatedCount(0, "< 10", 10));
        assertThat(out.get("\\axis\\").get("lo")).isEqualTo(new ObfuscatedCount(15, "15 ±5", 5));
        assertThat(out.get("\\axis\\").get("mid")).isEqualTo(new ObfuscatedCount(15, "15 ±5", 5));
        assertThat(out.get("\\axis\\").get("hi")).isEqualTo(new ObfuscatedCount(15, "15 ±5", 5));
        assertThat(out.get("\\axis\\").get("next")).isEqualTo(new ObfuscatedCount(25, "25 ±5", 5));
    }

    /** A value between the count threshold and the chart threshold is published by COUNT but suppressed in a chart. */
    @Test
    void chartThresholdIsStricterThanTheCountThreshold() {
        ObfuscationService s = svc();
        assertThat(s.obfuscateCount("7")).isEqualTo("7");
        assertThat(s.obfuscateCategoricalCrossCount(buckets("v", 7)).get("\\axis\\").get("v"))
            .isEqualTo(new ObfuscatedCount(0, "< 10", 10));
    }

    @Test
    void categoricalBucketsAreSuppressedOrReportedAtTheirBucketMidpoint() {
        var out = svc(5, 25, 50, Integer.MAX_VALUE).obfuscateCategoricalCrossCount(buckets("male", 12, "female", 103, "other", 100));

        // 12 is below the explicit categorical threshold of 25
        assertThat(out.get("\\axis\\").get("male")).isEqualTo(new ObfuscatedCount(0, "< 25", 25));
        // 103 falls in [100, 125) -> midpoint 112, banded at half the threshold
        assertThat(out.get("\\axis\\").get("female")).isEqualTo(new ObfuscatedCount(112, "112 ±12", 12));
        // an exact multiple sits at the BOTTOM of its bucket and still reports the midpoint
        assertThat(out.get("\\axis\\").get("other")).isEqualTo(new ObfuscatedCount(112, "112 ±12", 12));
    }

    @Test
    void continuousBucketsUseTheContinuousThresholdNotTheCategoricalOne() {
        var out = svc(5, 25, 50, Integer.MAX_VALUE).obfuscateContinuousCrossCount(buckets("18-24", 30, "24-30", 103));

        // 30 clears the categorical threshold but not the continuous one
        assertThat(out.get("\\axis\\").get("18-24")).isEqualTo(new ObfuscatedCount(0, "< 50", 50));
        // 103 falls in [100, 150) -> midpoint 125, banded at half the threshold
        assertThat(out.get("\\axis\\").get("24-30")).isEqualTo(new ObfuscatedCount(125, "125 ±25", 25));
    }

    @Test
    void anExplicitChartThresholdIgnoresTheCountThreshold() {
        // the count threshold is deliberately huge; an explicitly configured chart threshold must not derive from it
        var out = svc(1000, 10, 10, Integer.MAX_VALUE).obfuscateCategoricalCrossCount(buckets("male", 100));
        assertThat(out.get("\\axis\\").get("male")).isEqualTo(new ObfuscatedCount(105, "105 ±5", 5));
    }

    @Test
    void suppressedBucketKeepsTheFullThresholdBandSoTheTruthStaysInside() {
        // published count is 0 but the truth can be 9, so half a threshold would not reach it
        var out = svc().obfuscateCategoricalCrossCount(buckets("rare", 9));
        assertThat(out.get("\\axis\\").get("rare")).isEqualTo(new ObfuscatedCount(0, "< 10", 10));
    }

    @Test
    void chartBucketsHaveNoHierarchyRollup() {
        // inner keys are labels, not concept paths, so a label that prefixes another is still just its own bucket
        var out = svc().obfuscateCategoricalCrossCount(buckets("a", 5, "a-and-b", 500));
        assertThat(out.get("\\axis\\").get("a")).isEqualTo(new ObfuscatedCount(0, "< 10", 10));
        assertThat(out.get("\\axis\\").get("a-and-b")).isEqualTo(new ObfuscatedCount(505, "505 ±5", 5));
    }
}
