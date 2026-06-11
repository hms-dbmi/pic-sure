package edu.harvard.hms.dbmi.avillach.hpds.test;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.*;
import edu.harvard.hms.dbmi.avillach.hpds.processing.v3.CountV3Processor;
import edu.harvard.hms.dbmi.avillach.hpds.test.util.BuildIntegrationTestEnvironment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SpringExtension.class)
@EnableAutoConfiguration
@SpringBootTest(classes = edu.harvard.hms.dbmi.avillach.hpds.service.HpdsApplication.class)
@ActiveProfiles("integration-test")
public class CountV3ProcessorIntegrationTest {

    @Autowired
    private CountV3Processor countProcessor;

    @BeforeAll
    public static void beforeAll() {
        BuildIntegrationTestEnvironment instance = BuildIntegrationTestEnvironment.INSTANCE;
    }

    @Test
    public void runCategoryCrossCounts_multipleConceptsNoFilters() {
        Query query = new Query(
            List.of("\\open_access-1000Genomes\\data\\SEX\\", "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\"), List.of(), null, null,
            ResultType.CROSS_COUNT, null, null
        );

        Map<String, Integer> crossCountsMap = countProcessor.runCrossCounts(query);
        assertEquals(2, crossCountsMap.size());
        assertEquals(4978, crossCountsMap.get("\\open_access-1000Genomes\\data\\SEX\\"));
        assertEquals(1126, crossCountsMap.get("\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\"));
    }

    @Test
    public void runCategoryCrossCounts_unknownConceptNoFilters() {
        Query query = new Query(
            List.of("\\open_access-1000Genomes\\data\\SEX\\", "\\open_access-1000Genomes\\data\\NOT_REAL_DOESNT_EXIST\\"), List.of(), null,
            null, ResultType.CROSS_COUNT, null, null
        );

        Map<String, Integer> crossCountsMap = countProcessor.runCrossCounts(query);
        assertEquals(2, crossCountsMap.size());
        assertEquals(4978, crossCountsMap.get("\\open_access-1000Genomes\\data\\SEX\\"));
        assertEquals(0, crossCountsMap.get("\\open_access-1000Genomes\\data\\NOT_REAL_DOESNT_EXIST\\"));
    }

    @Test
    public void runCategoryCrossCounts_multipleConceptsWithFilters() {
        PhenotypicFilter sexFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\SEX\\", Set.of("male"), null, null, null);
        PhenotypicFilter populationFilter =
            new PhenotypicFilter(PhenotypicFilterType.REQUIRED, "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", null, null, null, null);

        PhenotypicSubquery phenotypicSubquery = new PhenotypicSubquery(null, List.of(sexFilter, populationFilter), Operator.AND);

        Query query = new Query(
            List.of("\\open_access-1000Genomes\\data\\SEX\\", "\\open_access-1000Genomes\\data\\SYNTHETIC_HEIGHT\\"), List.of(),
            phenotypicSubquery, null, ResultType.CROSS_COUNT, null, null
        );

        Map<String, Integer> crossCountsMap = countProcessor.runCrossCounts(query);
        assertEquals(2, crossCountsMap.size());
        assertEquals(551, crossCountsMap.get("\\open_access-1000Genomes\\data\\SEX\\"));
        assertEquals(551, crossCountsMap.get("\\open_access-1000Genomes\\data\\SYNTHETIC_HEIGHT\\"));
    }

    @Test
    public void runCategoryCrossCounts_conceptsNoResults() {
        PhenotypicFilter sexFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\SEX\\", Set.of("vulcan"), null, null, null);
        PhenotypicFilter populationFilter = new PhenotypicFilter(
            PhenotypicFilterType.REQUIRED, "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\", null, null, 500.0, null
        );

        PhenotypicSubquery phenotypicSubquery = new PhenotypicSubquery(null, List.of(sexFilter, populationFilter), Operator.AND);

        Query query = new Query(
            List.of("\\open_access-1000Genomes\\data\\SEX\\", "\\open_access-1000Genomes\\data\\SYNTHETIC_HEIGHT\\"), List.of(),
            phenotypicSubquery, null, ResultType.CROSS_COUNT, null, null
        );

        Map<String, Integer> crossCountsMap = countProcessor.runCrossCounts(query);
        assertEquals(2, crossCountsMap.size());
        assertEquals(0, crossCountsMap.get("\\open_access-1000Genomes\\data\\SEX\\"));
        assertEquals(0, crossCountsMap.get("\\open_access-1000Genomes\\data\\SYNTHETIC_HEIGHT\\"));
    }

    @Test
    public void runCategoryCrossCounts_twoFilters() {
        PhenotypicFilter sexFilter = new PhenotypicFilter(
            PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\SEX\\", Set.of("male", "female"), null, null, null
        );
        PhenotypicFilter populationFilter = new PhenotypicFilter(
            PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\POPULATION NAME\\", Set.of("Finnish"), null, null, null
        );

        PhenotypicSubquery phenotypicSubquery = new PhenotypicSubquery(null, List.of(sexFilter, populationFilter), Operator.AND);
        Query query = new Query(List.of(), List.of(), phenotypicSubquery, null, ResultType.COUNT, null, null);

        Map<String, Map<String, Integer>> crossCounts = countProcessor.runCategoryCrossCounts(query);
        assertEquals(2, crossCounts.size());
        assertEquals(38, crossCounts.get("\\open_access-1000Genomes\\data\\SEX\\").get("male"));
        assertEquals(64, crossCounts.get("\\open_access-1000Genomes\\data\\SEX\\").get("female"));
        assertEquals(102, crossCounts.get("\\open_access-1000Genomes\\data\\POPULATION NAME\\").get("Finnish"));
    }

    /**
     * With an OR clause the cohort is broader than any single filter's values, so a cross count reports each concept's full
     * distribution across that cohort: SEX shows female (present via the OR'd POPULATION branch) alongside the called-out male, and
     * POPULATION shows the populations of the OR'd males alongside the called-out Finnish.
     */
    @Test
    public void runCategoryCrossCounts_twoFiltersOr_reportsFullCohortDistribution() {
        String sexPath = "\\open_access-1000Genomes\\data\\SEX\\";
        String populationPath = "\\open_access-1000Genomes\\data\\POPULATION NAME\\";
        PhenotypicFilter sexFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, sexPath, Set.of("male"), null, null, null);
        PhenotypicFilter populationFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, populationPath, Set.of("Finnish"), null, null, null);

        PhenotypicSubquery phenotypicSubquery = new PhenotypicSubquery(null, List.of(sexFilter, populationFilter), Operator.OR);
        Query query = new Query(List.of(), List.of(), phenotypicSubquery, null, ResultType.COUNT, null, null);

        Map<String, Map<String, Integer>> crossCounts = countProcessor.runCategoryCrossCounts(query);

        assertEquals(2, crossCounts.size());
        Map<String, Integer> sexCounts = crossCounts.get(sexPath);
        // female is in the cohort via the OR'd POPULATION branch, so it appears even though only male was filtered
        assertEquals(Set.of("male", "female"), sexCounts.keySet());
        assertEquals(2648, sexCounts.get("male"));
        assertTrue(sexCounts.get("female") > 0, "females in the OR cohort should be reported");

        Map<String, Integer> populationCounts = crossCounts.get(populationPath);
        // the OR'd males bring in their own populations alongside the called-out Finnish
        assertEquals(102, populationCounts.get("Finnish"));
        assertTrue(populationCounts.size() > 1, "populations of the OR'd males should be reported");
    }

    @Test
    public void runCategoryCrossCounts_snpFilter() {
        PhenotypicFilter sexFilter =
            new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\open_access-1000Genomes\\data\\SEX\\", Set.of("male"), null, null, null);
        Query query = new Query(
            List.of(), List.of(), sexFilter, List.of(new GenomicFilter("chr21,5032061,A,G", List.of("0/1", "1/1"), null, null)),
            ResultType.COUNT, null, null
        );

        Map<String, Map<String, Integer>> crossCounts = countProcessor.runCategoryCrossCounts(query);
        assertEquals(1, crossCounts.size());
        assertEquals(2, crossCounts.get("\\open_access-1000Genomes\\data\\SEX\\").get("male"));
    }

    /**
     * Two categorical filters on the SAME concept path, OR'd together. With the v3 query a user can add sex=male OR sex=female as two
     * separate filters; the result must be a SINGLE chart entry for SEX that contains BOTH values, not just the last filter's value.
     */
    @Test
    public void runCategoryCrossCounts_duplicateCategoricalSamePathOr() {
        String sexPath = "\\open_access-1000Genomes\\data\\SEX\\";
        PhenotypicFilter maleFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, sexPath, Set.of("male"), null, null, null);
        PhenotypicFilter femaleFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, sexPath, Set.of("female"), null, null, null);
        PhenotypicSubquery subquery = new PhenotypicSubquery(null, List.of(maleFilter, femaleFilter), Operator.OR);
        Query query = new Query(List.of(), List.of(), subquery, null, ResultType.COUNT, null, null);

        Map<String, Map<String, Integer>> crossCounts = countProcessor.runCategoryCrossCounts(query);

        assertEquals(1, crossCounts.size());
        Map<String, Integer> sexCounts = crossCounts.get(sexPath);
        assertEquals(Set.of("male", "female"), sexCounts.keySet());
        assertEquals(2648, sexCounts.get("male"));
        assertEquals(2330, sexCounts.get("female"));
    }

    /**
     * Two categorical filters on the same path, AND'd with disjoint values (sex=male AND sex=female) yields an empty cohort. The single SEX
     * entry must still list both values, each at zero, rather than dropping one to a map-key collision.
     */
    @Test
    public void runCategoryCrossCounts_duplicateCategoricalSamePathAndDisjoint() {
        String sexPath = "\\open_access-1000Genomes\\data\\SEX\\";
        PhenotypicFilter maleFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, sexPath, Set.of("male"), null, null, null);
        PhenotypicFilter femaleFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, sexPath, Set.of("female"), null, null, null);
        PhenotypicSubquery subquery = new PhenotypicSubquery(null, List.of(maleFilter, femaleFilter), Operator.AND);
        Query query = new Query(List.of(), List.of(), subquery, null, ResultType.COUNT, null, null);

        Map<String, Map<String, Integer>> crossCounts = countProcessor.runCategoryCrossCounts(query);

        assertEquals(1, crossCounts.size());
        Map<String, Integer> sexCounts = crossCounts.get(sexPath);
        assertEquals(Set.of("male", "female"), sexCounts.keySet());
        assertEquals(0, sexCounts.get("male"));
        assertEquals(0, sexCounts.get("female"));
    }

    /**
     * A REQUIRED filter and a value filter on the same path, AND'd, narrow the cohort to males. female has no members in that cohort and
     * was not explicitly selected, so it is omitted (it is not "called out" by a value filter).
     */
    @Test
    public void runCategoryCrossCounts_requiredPlusFilterSamePath() {
        String sexPath = "\\open_access-1000Genomes\\data\\SEX\\";
        PhenotypicFilter requiredSex = new PhenotypicFilter(PhenotypicFilterType.REQUIRED, sexPath, null, null, null, null);
        PhenotypicFilter maleFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, sexPath, Set.of("male"), null, null, null);
        PhenotypicSubquery subquery = new PhenotypicSubquery(null, List.of(requiredSex, maleFilter), Operator.AND);
        Query query = new Query(List.of(), List.of(), subquery, null, ResultType.COUNT, null, null);

        Map<String, Map<String, Integer>> crossCounts = countProcessor.runCategoryCrossCounts(query);

        assertEquals(1, crossCounts.size());
        Map<String, Integer> sexCounts = crossCounts.get(sexPath);
        assertEquals(Set.of("male"), sexCounts.keySet());
        assertEquals(2648, sexCounts.get("male"));
    }

    /**
     * Mirrors the reported bug: a value filter (SEX=male) OR'd with a REQUIRED filter on a different concept. The REQUIRED branch pulls
     * females into the cohort, so the SEX chart must show female alongside male rather than male alone.
     */
    @Test
    public void runCategoryCrossCounts_valueFilterOrRequiredOnOtherConcept_showsUnfilteredValues() {
        String sexPath = "\\open_access-1000Genomes\\data\\SEX\\";
        String populationPath = "\\open_access-1000Genomes\\data\\POPULATION NAME\\";
        PhenotypicFilter maleFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, sexPath, Set.of("male"), null, null, null);
        PhenotypicFilter requiredPopulation = new PhenotypicFilter(PhenotypicFilterType.REQUIRED, populationPath, null, null, null, null);

        PhenotypicSubquery subquery = new PhenotypicSubquery(null, List.of(maleFilter, requiredPopulation), Operator.OR);
        Query query = new Query(List.of(), List.of(), subquery, null, ResultType.COUNT, null, null);

        Map<String, Map<String, Integer>> crossCounts = countProcessor.runCategoryCrossCounts(query);

        Map<String, Integer> sexCounts = crossCounts.get(sexPath);
        assertEquals(Set.of("male", "female"), sexCounts.keySet());
        assertEquals(2648, sexCounts.get("male"));
        assertTrue(sexCounts.get("female") > 0, "females pulled into the cohort by the REQUIRED branch should be reported");
    }

    /**
     * A REQUIRED filter on a continuous concept must not produce a categorical cross-count entry: continuous concepts have no categorical
     * distribution and are handled by runContinuousCrossCounts instead.
     */
    @Test
    public void runCategoryCrossCounts_requiredContinuousConcept_isExcluded() {
        String agePath = "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\";
        PhenotypicFilter requiredAge = new PhenotypicFilter(PhenotypicFilterType.REQUIRED, agePath, null, null, null, null);
        Query query = new Query(List.of(), List.of(), requiredAge, null, ResultType.COUNT, null, null);

        Map<String, Map<String, Integer>> crossCounts = countProcessor.runCategoryCrossCounts(query);

        assertFalse(crossCounts.containsKey(agePath), "a continuous concept should not appear in categorical cross counts");
    }

    /**
     * Two numeric range filters on the same path, OR'd, must produce a single entry whose keys are the UNION of the two ranges, not a
     * widened [min,max] span: a value sitting in the gap between the ranges must NOT appear.
     */
    @Test
    public void runContinuousCrossCounts_duplicateRangesSamePathOr() {
        String agePath = "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\";
        PhenotypicFilter youngFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, agePath, null, 31.0, 35.0, null);
        PhenotypicFilter oldFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, agePath, null, 55.0, 62.0, null);
        PhenotypicSubquery subquery = new PhenotypicSubquery(null, List.of(youngFilter, oldFilter), Operator.OR);
        Query query = new Query(List.of(), List.of(), subquery, null, ResultType.COUNT, null, null);

        Map<String, Map<Double, Integer>> crossCounts = countProcessor.runContinuousCrossCounts(query);

        assertEquals(1, crossCounts.size());
        Map<Double, Integer> ageCounts = crossCounts.get(agePath);
        assertTrue(ageCounts.containsKey(31.0), "low range endpoint should be present");
        assertTrue(ageCounts.containsKey(62.0), "high range endpoint should be present");
        assertFalse(ageCounts.containsKey(44.0), "a value in the gap between the two ranges must not appear");
    }

    /**
     * A continuous range filter OR'd with a filter on another concept: the age distribution must include ages outside the filtered [55,62]
     * range, because the OR'd males (of all ages) are in the cohort. The range only constrains the cohort, not which values are displayed.
     */
    @Test
    public void runContinuousCrossCounts_rangeOrOtherConcept_showsValuesOutsideRange() {
        String agePath = "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\";
        String sexPath = "\\open_access-1000Genomes\\data\\SEX\\";
        PhenotypicFilter oldFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, agePath, null, 55.0, 62.0, null);
        PhenotypicFilter maleFilter = new PhenotypicFilter(PhenotypicFilterType.FILTER, sexPath, Set.of("male"), null, null, null);
        PhenotypicSubquery subquery = new PhenotypicSubquery(null, List.of(oldFilter, maleFilter), Operator.OR);
        Query query = new Query(List.of(), List.of(), subquery, null, ResultType.COUNT, null, null);

        Map<String, Map<Double, Integer>> crossCounts = countProcessor.runContinuousCrossCounts(query);

        Map<Double, Integer> ageCounts = crossCounts.get(agePath);
        assertTrue(
            ageCounts.keySet().stream().anyMatch(age -> age < 55.0),
            "ages below the filtered range should appear via the OR'd males in the cohort"
        );
    }

}
