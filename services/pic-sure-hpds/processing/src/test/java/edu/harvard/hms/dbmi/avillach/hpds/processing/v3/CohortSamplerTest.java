package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class CohortSamplerTest {

    private static final String CONSENTS = AuthorizationFilter.CONSENTS_CONCEPT_PATH;
    private static final String HARMONIZED = "\\_harmonized_consent\\";
    private static final String TOPMED = "\\_topmed_consents\\";
    private static final String SALT = "fixed-test-salt";
    private static final String MISSING_CONCEPT_WARNING = "the consents concept is missing from the store or does not hold text values";
    private static final String NO_MEMBER_WARNING = "has no member in any of";

    @Mock
    private PhenotypicObservationStore store;

    @Test
    void cohortAtOrBelowMinSizeReturnsEveryone() {
        Set<Integer> sixty = range(1, 60);
        Set<Integer> hundred = range(1, 100);

        assertEquals(sixty, sample(100, SALT, consentsQuery("phs000001.c1"), sixty));
        assertEquals(hundred, sample(100, SALT, consentsQuery("phs000001.c1"), hundred));
        verifyNoInteractions(store);
    }

    @Test
    void moreGroupsThanMinSizeReturnsOnePerGroup() {
        Map<String, Set<Integer>> groups = new TreeMap<>();
        for (int group = 0; group < 150; group++) {
            groups.put("phs%06d.c1".formatted(group), Set.of(group * 10, group * 10 + 1, group * 10 + 2));
        }
        Map<String, TreeSet<Integer>> live = storeConsents(groups);
        Set<Integer> cohort = groups.values().stream().flatMap(Set::stream).collect(Collectors.toCollection(HashSet::new));

        Set<Integer> sample = sample(100, SALT, consentsQuery(groups.keySet().toArray(String[]::new)), cohort);

        assertEquals(150, sample.size());
        live.values().forEach(members -> assertEquals(1, members.stream().filter(sample::contains).count()));
    }

    @Test
    void noGroupsReturnsMinSizeFromTheCohort() {
        Set<Integer> cohort = range(1, 1000);

        Set<Integer> sample = sample(100, SALT, query(List.of()), cohort);

        assertEquals(100, sample.size());
    }

    @Test
    void noConsentsFilterGivesOneGroupOfTheCohort() {
        Set<Integer> cohort = range(1, 1000);
        Query query = query(List.of(new AuthorizationFilter(HARMONIZED, Set.of("harmonized.c1"))));

        Set<Integer> sample = sample(100, SALT, query, cohort);

        assertEquals(100, sample.size());
        assertEquals(sample(100, SALT, query(List.of()), cohort), sample);
        verifyNoInteractions(store);
        List<Integer> ordered = List.copyOf(new TreeSet<>(sample));
        long breaks = IntStream.range(1, ordered.size()).filter(index -> ordered.get(index) != ordered.get(index - 1) + 1).count();
        assertTrue(breaks <= 1, "one cursor walks upward and wraps at most once");
    }

    @Test
    void repeatedRequestReturnsTheSameIds() {
        storeConsents(Map.of("phs000001.c1", range(1, 250), "phs000002.c1", range(221, 400)));
        Set<Integer> cohort = range(1, 400);
        Query query = consentsQuery("phs000001.c1", "phs000002.c1");
        CohortSampler sampler = sampler(100, SALT);

        Set<Integer> first = sampler.sample(query, cohort);
        Set<Integer> second = sampler.sample(query, cohort);
        Set<Integer> fromAnotherInstance = sample(100, SALT, query, cohort);

        assertTrue(cohort.containsAll(first));
        assertEquals(first, second);
        assertEquals(first, fromAnotherInstance);
    }

    @Test
    void fixedSaltAndDataGiveThePinnedIds() {
        storeConsents(Map.of("phs000001.c1", range(1, 50), "phs000002.c1", range(40, 90)));

        Set<Integer> sample = sample(10, SALT, consentsQuery("phs000001.c1", "phs000002.c1"), range(1, 90));

        assertEquals(PINNED_IDS, sample);
    }

    private static final Set<Integer> PINNED_IDS = Set.of(17, 18, 19, 20, 21, 63, 64, 65, 66, 67);

    @Test
    void sampleNeverHoldsAParticipantOutsideTheCallersGroups() {
        storeConsents(Map.of("phs000001.c1", range(1, 250), "phs000002.c1", range(221, 400)));
        Set<Integer> cohort = range(1, 400);

        Set<Integer> sample = sample(100, SALT, consentsQuery("phs000001.c1"), cohort);

        assertEquals(100, sample.size());
        assertTrue(range(1, 250).containsAll(sample));
    }

    @Test
    void twoGroupsAreBothRepresented() {
        storeConsents(Map.of("phs000001.c1", range(1, 250), "phs000002.c1", range(221, 400)));
        Set<Integer> cohort = range(1, 400);

        Set<Integer> sample = sample(100, SALT, consentsQuery("phs000001.c1", "phs000002.c1"), cohort);

        assertEquals(100, sample.size());
        assertTrue(sample.stream().filter(range(1, 250)::contains).count() >= 50);
        assertTrue(sample.stream().filter(range(221, 400)::contains).count() >= 50);
    }

    @Test
    void severalConsentsEntriesUseTheUnionOfTheirValues() {
        storeConsents(Map.of("phs000001.c1", range(1, 300), "phs000002.c1", range(301, 600), "phs000003.c1", range(601, 900)));
        Set<Integer> cohort = range(1, 900);
        Query query = query(
            List.of(new AuthorizationFilter(CONSENTS, Set.of("phs000001.c1")), new AuthorizationFilter(CONSENTS, Set.of("phs000002.c1")))
        );

        Set<Integer> sample = sample(100, SALT, query, cohort);

        assertEquals(100, sample.size());
        assertEquals(50, sample.stream().filter(range(1, 300)::contains).count());
        assertEquals(50, sample.stream().filter(range(301, 600)::contains).count());
    }

    @Test
    void participantInTwoGroupsIsTakenOnce() {
        storeConsents(Map.of("phs000001.c1", range(1, 300), "phs000002.c1", range(1, 300)));

        Set<Integer> sample = sample(100, SALT, consentsQuery("phs000001.c1", "phs000002.c1"), range(1, 300));

        assertEquals(100, sample.size());
    }

    @Test
    void groupThatRunsShortDropsOut() {
        storeConsents(Map.of("phs000001.c1", Set.of(5, 6, 7, 2000, 2001), "phs000002.c1", range(100, 600)));
        Set<Integer> cohort = range(1, 600);

        Set<Integer> sample = sample(100, SALT, consentsQuery("phs000001.c1", "phs000002.c1"), cohort);

        assertEquals(100, sample.size());
        assertTrue(sample.containsAll(Set.of(5, 6, 7)));
        assertEquals(97, sample.stream().filter(range(100, 600)::contains).count());
    }

    @Test
    void groupsCoveringFewerThanMinSizeAreNotPadded() {
        storeConsents(Map.of("phs000001.c1", range(1, 40)));

        Set<Integer> sample = sample(100, SALT, consentsQuery("phs000001.c1"), range(1, 500));

        assertEquals(range(1, 40), sample);
    }

    @Test
    void consentsFilterWithNoMatchingGroupGivesAnEmptySample(CapturedOutput output) {
        storeConsents(Map.of("phs000001.c1", range(5000, 5100)));
        Set<Integer> cohort = range(1, 500);

        assertEquals(Set.of(), sample(100, SALT, consentsQuery("phs000001.c1"), cohort));
        assertEquals(Set.of(), sample(100, SALT, consentsQuery("phs999999.c1"), cohort));
        assertEquals(Set.of(), sample(100, SALT, consentsQuery(), cohort));
        assertEquals(Set.of(), sample(100, SALT, query(List.of(new AuthorizationFilter(CONSENTS, null))), cohort));

        assertTrue(output.getOut().contains("cohort of 500 participants has no member in any of the 1 consent groups"));
        assertFalse(output.getOut().contains("phs000001"));
        assertFalse(output.getOut().contains(MISSING_CONCEPT_WARNING));
    }

    @Test
    void missingConsentsConceptGivesAnEmptySample(CapturedOutput output) {
        when(store.getCube(CONSENTS)).thenReturn(Optional.empty());

        assertEquals(Set.of(), sample(100, SALT, consentsQuery("phs000001.c1", "phs000002.c1"), range(1, 500)));

        assertTrue(output.getOut().contains(MISSING_CONCEPT_WARNING));
        assertTrue(output.getOut().contains("none of the 2 consent groups of the query could be read for the cohort of 500 participants"));
        assertFalse(output.getOut().contains(NO_MEMBER_WARNING));
        assertFalse(output.getOut().contains("phs00000"));
        assertFalse(output.getOut().contains(SALT));
    }

    @Test
    void consentsConceptThatIsNotTextGivesAnEmptySample(CapturedOutput output) {
        when(store.getCube(CONSENTS)).thenReturn(Optional.of(new PhenoCube<>(CONSENTS, Double.class)));

        assertEquals(Set.of(), sample(100, SALT, consentsQuery("phs000001.c1"), range(1, 500)));

        assertTrue(output.getOut().contains(MISSING_CONCEPT_WARNING));
        assertTrue(output.getOut().contains("none of the 1 consent groups of the query could be read for the cohort of 500 participants"));
        assertFalse(output.getOut().contains(NO_MEMBER_WARNING));
        assertFalse(output.getOut().contains("phs000001"));
    }

    @Test
    void harmonizedAndTopmedFiltersAddNoGroups() {
        storeConsents(Map.of("phs000001.c1", range(1, 300)));
        Query query = query(
            List.of(
                new AuthorizationFilter(HARMONIZED, Set.of("harmonized.c1")), new AuthorizationFilter(CONSENTS, Set.of("phs000001.c1")),
                new AuthorizationFilter(TOPMED, Set.of("topmed.c1"))
            )
        );

        Set<Integer> sample = sample(100, SALT, query, range(1, 600));

        assertEquals(100, sample.size());
        assertTrue(range(1, 300).containsAll(sample));
        verify(store, never()).getCube(HARMONIZED);
        verify(store, never()).getCube(TOPMED);
    }

    @Test
    void differentSaltGivesADifferentSample() {
        storeConsents(Map.of("phs000001.c1", range(1, 5000), "phs000002.c1", range(5001, 10000)));
        Set<Integer> cohort = range(1, 10000);
        Query query = consentsQuery("phs000001.c1", "phs000002.c1");

        assertNotEquals(sample(100, "salt-one", query, cohort), sample(100, "salt-two", query, cohort));
    }

    @Test
    void cohortSetTypeDoesNotChangeTheSample() {
        storeConsents(Map.of("phs000001.c1", range(1, 5000), "phs000002.c1", range(4000, 4050)));
        Query query = consentsQuery("phs000001.c1", "phs000002.c1");
        Set<Integer> hashed = new HashSet<>(range(3000, 4500));

        Set<Integer> fromHashSet = sample(100, SALT, query, hashed);
        Set<Integer> fromTreeSet = sample(100, SALT, query, new TreeSet<>(hashed));

        assertEquals(100, fromHashSet.size());
        assertEquals(fromHashSet, fromTreeSet);
    }

    @Test
    void groupLargerThanTheCohortStaysInsideBoth() {
        storeConsents(Map.of("phs000001.c1", range(1, 20000)));
        Set<Integer> cohort = IntStream.rangeClosed(1, 400).map(id -> id * 100).boxed().collect(Collectors.toCollection(HashSet::new));

        Set<Integer> sample = sample(100, SALT, consentsQuery("phs000001.c1"), cohort);

        assertEquals(100, sample.size());
        assertTrue(range(1, 20000).containsAll(sample));
    }

    @Test
    void groupMemberSetIsNotModified() {
        Map<String, TreeSet<Integer>> live = storeConsents(Map.of("phs000001.c1", range(1, 250), "phs000002.c1", range(221, 400)));
        Map<String, TreeSet<Integer>> before = new TreeMap<>();
        live.forEach((value, members) -> before.put(value, new TreeSet<>(members)));
        PhenoCube<?> cube = store.getCube(CONSENTS).orElseThrow();

        sample(100, SALT, consentsQuery("phs000001.c1", "phs000002.c1"), range(1, 400));
        sample(100, SALT, consentsQuery("phs000001.c1", "phs000002.c1"), range(200, 320));

        assertEquals(before, live);
        @SuppressWarnings("unchecked")
        PhenoCube<String> stringCube = (PhenoCube<String>) cube;
        live.forEach((value, members) -> assertSame(members, stringCube.getKeysForValue(value)));
    }

    @Test
    void walkingTheCohortNeverTakesAParticipantOutsideTheGroup() {
        Set<Integer> group = range(1, 1000);
        storeConsents(Map.of("phs000001.c1", group, "phs000002.c1", range(-5000, 6000)));
        Set<Integer> cohortWithFewInTheGroup = union(range(-219, 0), range(471, 530), range(1001, 1220));
        Set<Integer> cohortWithManyInTheGroup = union(range(-149, 0), range(401, 600), range(1001, 1150));

        Set<Integer> allOfTheFew = sample(100, SALT, consentsQuery("phs000001.c1"), cohortWithFewInTheGroup);
        Set<Integer> someOfTheMany = sample(100, SALT, consentsQuery("phs000001.c1"), cohortWithManyInTheGroup);

        assertEquals(500, cohortWithFewInTheGroup.size());
        assertEquals(500, cohortWithManyInTheGroup.size());
        assertEquals(range(471, 530), allOfTheFew);
        assertEquals(100, someOfTheMany.size());
        assertTrue(group.containsAll(someOfTheMany));
        assertTrue(cohortWithManyInTheGroup.containsAll(someOfTheMany));
    }

    @Test
    void walkingTheMemberSetNeverTakesAParticipantOutsideTheCohort() {
        Set<Integer> group = range(1, 300);
        storeConsents(Map.of("phs000001.c1", group));
        Set<Integer> cohortWithFewInTheGroup = range(251, 650);
        Set<Integer> cohortWithManyInTheGroup = range(151, 600);

        Set<Integer> allOfTheFew = sampler(100, SALT).sample(consentsQuery("phs000001.c1"), cohortWithFewInTheGroup);
        Set<Integer> someOfTheMany = sampler(100, SALT).sample(consentsQuery("phs000001.c1"), cohortWithManyInTheGroup);

        assertEquals(range(251, 300), allOfTheFew);
        assertEquals(100, someOfTheMany.size());
        assertTrue(group.containsAll(someOfTheMany));
        assertTrue(cohortWithManyInTheGroup.containsAll(someOfTheMany));
    }

    @Test
    void memberSetSmallerThanTheCohortGivesThePinnedIds() {
        storeConsents(Map.of("phs000001.c1", everyThird(399)));

        assertEquals(PINNED_FROM_THE_MEMBER_WALK, sample(10, SALT, consentsQuery("phs000001.c1"), EVEN_IDS_TO_800));
    }

    @Test
    void memberSetTheSizeOfTheCohortGivesThePinnedIds() {
        storeConsents(Map.of("phs000001.c1", everyThird(400)));

        assertEquals(400, EVEN_IDS_TO_800.size());
        assertEquals(PINNED_FROM_THE_MEMBER_WALK, sample(10, SALT, consentsQuery("phs000001.c1"), EVEN_IDS_TO_800));
    }

    @Test
    void memberSetLargerThanTheCohortGivesThePinnedIds() {
        storeConsents(Map.of("phs000001.c1", everyThird(401)));

        assertEquals(PINNED_FROM_THE_COHORT_WALK, sample(10, SALT, consentsQuery("phs000001.c1"), EVEN_IDS_TO_800));
    }

    private static final Set<Integer> EVEN_IDS_TO_800 = IntStream.rangeClosed(1, 400).map(id -> id * 2).boxed().collect(Collectors.toSet());
    private static final Set<Integer> PINNED_FROM_THE_MEMBER_WALK = Set.of(600, 606, 612, 618, 624, 630, 636, 642, 648, 654);
    private static final Set<Integer> PINNED_FROM_THE_COHORT_WALK = Set.of(66, 72, 78, 84, 90, 96, 102, 108, 114, 120);

    @Test
    void extremeIdsInTheWalkedCohortStayInsideTheCohort() {
        Set<Integer> cohort = new HashSet<>(Set.of(Integer.MIN_VALUE, -2_000_000_000, -5, -1, 0, 7, 2_000_000_000, Integer.MAX_VALUE));
        Query query = query(List.of());

        Set<Integer> sample = sample(3, SALT, query, cohort);

        assertEquals(3, sample.size());
        assertEquals(sample, sample(3, SALT, query, cohort));
    }

    @Test
    void extremeIdsInTheWalkedMemberSetStayInsideTheCohort() {
        Set<Integer> members = Set.of(Integer.MIN_VALUE, -40, -3, 0, 12, Integer.MAX_VALUE);
        storeConsents(Map.of("phs000001.c1", members));
        Set<Integer> cohort = union(range(-10, 10), Set.of(Integer.MIN_VALUE, Integer.MAX_VALUE));
        Query query = consentsQuery("phs000001.c1");

        Set<Integer> sample = sample(3, SALT, query, cohort);

        assertEquals(3, sample.size());
        assertTrue(members.containsAll(sample));
        assertEquals(sample, sample(3, SALT, query, cohort));
        assertEquals(Set.of(Integer.MIN_VALUE, -3, 0, Integer.MAX_VALUE), sample(10, SALT, query, cohort));
    }

    @Test
    void extremeIdsInACohortWalkedForALargerGroupStayInsideBoth() {
        Set<Integer> members = union(range(-50, 50), Set.of(Integer.MIN_VALUE, Integer.MAX_VALUE));
        storeConsents(Map.of("phs000001.c1", members));
        Set<Integer> cohort = new HashSet<>(Set.of(Integer.MIN_VALUE, -2_000_000_000, -40, -7, 3, 99, 2_000_000_000, Integer.MAX_VALUE));
        Query query = consentsQuery("phs000001.c1");

        Set<Integer> sample = sample(3, SALT, query, cohort);

        assertEquals(3, sample.size());
        assertTrue(members.containsAll(sample));
        assertEquals(sample, sample(3, SALT, query, cohort));
        assertEquals(Set.of(Integer.MIN_VALUE, -40, -7, 3, Integer.MAX_VALUE), sample(7, SALT, query, cohort));
    }

    @Test
    void singleMemberGroupGivesThatMemberWhenTheCohortHoldsIt() {
        storeConsents(
            Map.of(
                "phs000001.c1", Set.of(-7), "phs000002.c1", Set.of(Integer.MIN_VALUE), "phs000003.c1", Set.of(Integer.MAX_VALUE),
                "phs000004.c1", Set.of(4000)
            )
        );
        Set<Integer> cohort = union(range(-20, 20), Set.of(Integer.MIN_VALUE, Integer.MAX_VALUE));

        assertEquals(Set.of(-7), sample(5, SALT, consentsQuery("phs000001.c1"), cohort));
        assertEquals(Set.of(Integer.MIN_VALUE), sample(5, SALT, consentsQuery("phs000002.c1"), cohort));
        assertEquals(Set.of(Integer.MAX_VALUE), sample(5, SALT, consentsQuery("phs000003.c1"), cohort));
        assertEquals(Set.of(), sample(5, SALT, consentsQuery("phs000004.c1"), cohort));
        assertEquals(
            Set.of(Integer.MIN_VALUE, -7, Integer.MAX_VALUE),
            sample(5, SALT, consentsQuery("phs000001.c1", "phs000002.c1", "phs000003.c1", "phs000004.c1"), cohort)
        );
    }

    @Test
    void negativeIdsAreSampledLikeAnyOther() {
        storeConsents(Map.of("phs000001.c1", range(-900, -600), "phs000002.c1", range(-2000, -1)));
        Set<Integer> cohort = range(-700, -200);
        Query oneGroupWalkingItsMembers = consentsQuery("phs000001.c1");
        Query oneGroupWalkingTheCohort = consentsQuery("phs000002.c1");

        Set<Integer> fromMembers = sample(20, SALT, oneGroupWalkingItsMembers, cohort);
        Set<Integer> fromCohort = sample(20, SALT, oneGroupWalkingTheCohort, cohort);

        assertEquals(20, fromMembers.size());
        assertTrue(range(-700, -600).containsAll(fromMembers));
        assertEquals(fromMembers, sample(20, SALT, oneGroupWalkingItsMembers, cohort));
        assertEquals(20, fromCohort.size());
        assertEquals(fromCohort, sample(20, SALT, oneGroupWalkingTheCohort, cohort));
    }

    private Set<Integer> sample(int minSize, String salt, Query query, Set<Integer> cohort) {
        Set<Integer> cohortBefore = Set.copyOf(cohort);
        Set<Integer> sample = sampler(minSize, salt).sample(query, cohort);
        assertTrue(cohort.containsAll(sample), "the sample must be a subset of the cohort");
        assertEquals(cohortBefore, cohort, "the cohort must not be modified");
        return sample;
    }

    private CohortSampler sampler(int minSize, String salt) {
        SampleProperties properties = new SampleProperties();
        properties.setMinSize(minSize);
        properties.setSalt(salt);
        return new CohortSampler(store, properties);
    }

    private Map<String, TreeSet<Integer>> storeConsents(Map<String, Set<Integer>> membersByValue) {
        TreeMap<String, TreeSet<Integer>> categoryMap = new TreeMap<>();
        membersByValue.forEach((value, members) -> categoryMap.put(value, new TreeSet<>(members)));
        PhenoCube<String> cube = new PhenoCube<>(CONSENTS, String.class);
        cube.setCategoryMap(categoryMap);
        when(store.getCube(anyString())).thenAnswer(invocation -> {
            assertEquals(CONSENTS, invocation.getArgument(0), "only the consents concept may be read");
            return Optional.of(cube);
        });
        return categoryMap;
    }

    private static Query consentsQuery(String... values) {
        return query(List.of(new AuthorizationFilter(CONSENTS, new HashSet<>(Arrays.asList(values)))));
    }

    private static Query query(List<AuthorizationFilter> authorizationFilters) {
        return new Query(List.of("\\demographics\\age\\"), authorizationFilters, null, List.of(), ResultType.DATAFRAME, null, null, true);
    }

    private static Set<Integer> union(Set<Integer> first, Set<Integer> second, Set<Integer> third) {
        return union(union(first, second), third);
    }

    private static Set<Integer> union(Set<Integer> first, Set<Integer> second) {
        Set<Integer> all = new HashSet<>(first);
        all.addAll(second);
        return all;
    }

    private static Set<Integer> everyThird(int count) {
        return IntStream.rangeClosed(1, count).map(id -> id * 3).boxed().collect(Collectors.toCollection(HashSet::new));
    }

    private static Set<Integer> range(int first, int last) {
        return IntStream.rangeClosed(first, last).boxed().collect(Collectors.toCollection(HashSet::new));
    }
}
