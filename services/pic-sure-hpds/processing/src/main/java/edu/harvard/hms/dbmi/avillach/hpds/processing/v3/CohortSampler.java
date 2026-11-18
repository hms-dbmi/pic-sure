package edu.harvard.hms.dbmi.avillach.hpds.processing.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Picks the participants of a sample export from the cohort a query matched.
 *
 * <p>The sampler runs on the set returned by {@link QueryExecutor#getPatientSubsetForQuery(Query)} and only ever returns ids from that set,
 * so a sample holds nobody the full export would not.
 *
 * <p>Selection rules: <ol> <li>A cohort of {@code sample.min-size} participants or fewer is returned whole.</li> <li>The groups are the
 * values of the query's authorization filters on {@link AuthorizationFilter#CONSENTS_CONCEPT_PATH}. Several such filters contribute the
 * union of their values. A group's members are the participants stored under that value, intersected with the cohort. Groups are never read
 * from a participant's own consent values, and no other authorization filter adds a group.</li> <li>When the query has no authorization
 * filter on that path at all, the cohort is one group. When it has one, the sample is drawn only from its groups: cohort members in no
 * group are never selected, and if no group has a cohort member the sample is empty.</li> <li>Groups take turns in name order. A turn takes
 * the group's next participant who is in the cohort and not yet selected. A participant in several groups is selected once. A group with
 * nobody left leaves the rotation.</li> <li>The first round takes one participant from every group, even past {@code sample.min-size}.
 * After it, selection stops as soon as the sample holds {@code sample.min-size} participants or no group has anyone left.</li> </ol>
 *
 * <p>Each group visits its candidates in ascending id order, starting at an id derived from SHA-256 of the salt and the group value and
 * wrapping around to the lowest id. A group walks whichever of its member set and the cohort is smaller and tests each candidate against
 * the other. The cohort is sorted at most once per call and member sets are never sorted, copied or modified. The same salt, query and
 * cohort always give the same sample.
 */
@Component
public class CohortSampler {

    private static final Logger log = LoggerFactory.getLogger(CohortSampler.class);

    private static final String WHOLE_COHORT_GROUP = "";

    private final PhenotypicObservationStore phenotypicObservationStore;
    private final SampleProperties sampleProperties;

    @Autowired
    public CohortSampler(PhenotypicObservationStore phenotypicObservationStore, SampleProperties sampleProperties) {
        this.phenotypicObservationStore = phenotypicObservationStore;
        this.sampleProperties = sampleProperties;
    }

    /**
     * Selects the sample for a query.
     *
     * @param query the query, whose consent authorization filters name the groups
     * @param cohort the participants the query matched, which is read and never modified
     * @return a new sorted set holding the sampled ids, always a subset of the cohort
     */
    public Set<Integer> sample(Query query, Set<Integer> cohort) {
        int minSize = sampleProperties.getMinSize();
        if (cohort.size() <= minSize) {
            return new TreeSet<>(cohort);
        }
        List<AuthorizationFilter> consentFilters = query.authorizationFilters().stream()
            .filter(filter -> AuthorizationFilter.CONSENTS_CONCEPT_PATH.equals(filter.conceptPath())).toList();
        SortedCohort sortedCohort = new SortedCohort(cohort);
        if (consentFilters.isEmpty()) {
            return select(List.of(new GroupCursor(WHOLE_COHORT_GROUP, sortedCohort.get(), null)), minSize);
        }
        Set<String> groupValues = groupValues(consentFilters);
        Optional<PhenoCube<String>> consents = consentsCube();
        if (consents.isEmpty()) {
            log.warn(
                "Sample is empty because the consents concept is missing from the store or does not hold text values, so none of the {} "
                    + "consent groups of the query could be read for the cohort of {} participants",
                groupValues.size(), cohort.size()
            );
            return new TreeSet<>();
        }
        List<GroupCursor> groups = consentGroups(consents.get(), groupValues, cohort, sortedCohort);
        TreeSet<Integer> sample = select(groups, minSize);
        if (sample.isEmpty()) {
            log.warn(
                "Sample is empty because the cohort of {} participants has no member in any of the {} consent groups of the query",
                cohort.size(), groups.size()
            );
        }
        return sample;
    }

    private static Set<String> groupValues(List<AuthorizationFilter> consentFilters) {
        TreeSet<String> values = new TreeSet<>();
        consentFilters.stream().map(AuthorizationFilter::values).filter(Objects::nonNull)
            .forEach(filterValues -> filterValues.stream().filter(Objects::nonNull).forEach(values::add));
        return values;
    }

    @SuppressWarnings("unchecked")
    private Optional<PhenoCube<String>> consentsCube() {
        return phenotypicObservationStore.getCube(AuthorizationFilter.CONSENTS_CONCEPT_PATH).filter(PhenoCube::isStringType)
            .map(cube -> (PhenoCube<String>) cube);
    }

    private List<GroupCursor> consentGroups(
        PhenoCube<String> consents, Set<String> groupValues, Set<Integer> cohort, SortedCohort sortedCohort
    ) {
        List<GroupCursor> groups = new ArrayList<>();
        for (String value : groupValues) {
            Set<Integer> members = consents.getKeysForValue(value);
            if (members.size() <= cohort.size()) {
                groups.add(new GroupCursor(value, navigable(members), cohort));
            } else {
                groups.add(new GroupCursor(value, sortedCohort.get(), members));
            }
        }
        return groups;
    }

    private TreeSet<Integer> select(List<GroupCursor> groups, int minSize) {
        TreeSet<Integer> sample = new TreeSet<>();
        List<GroupCursor> rotation = new ArrayList<>(groups);
        takeOneFromEach(rotation, sample, Integer.MAX_VALUE);
        while (!rotation.isEmpty() && sample.size() < minSize) {
            takeOneFromEach(rotation, sample, minSize);
        }
        return sample;
    }

    private void takeOneFromEach(List<GroupCursor> rotation, TreeSet<Integer> sample, int stopAtSize) {
        Iterator<GroupCursor> turns = rotation.iterator();
        while (turns.hasNext() && sample.size() < stopAtSize) {
            Integer next = turns.next().next(sample);
            if (next == null) {
                turns.remove();
            } else {
                sample.add(next);
            }
        }
    }

    private static NavigableSet<Integer> navigable(Set<Integer> ids) {
        NavigableSet<Integer> sorted = ids instanceof NavigableSet<Integer> alreadySorted ? alreadySorted : new TreeSet<>(ids);
        return Collections.unmodifiableNavigableSet(sorted);
    }

    /**
     * The cohort in ascending id order, sorted on first use and at most once.
     */
    private static final class SortedCohort {

        private final Set<Integer> cohort;
        private NavigableSet<Integer> sorted;

        private SortedCohort(Set<Integer> cohort) {
            this.cohort = cohort;
        }

        private NavigableSet<Integer> get() {
            if (sorted == null) {
                sorted = navigable(cohort);
            }
            return sorted;
        }
    }

    /**
     * One group's position in its candidates. The candidates are walked upward from the start id to the highest id, then from the lowest id
     * up to the start id, so every candidate is visited exactly once.
     */
    private final class GroupCursor {

        private final Set<Integer> mustAlsoBeIn;
        private final Iterator<Integer> fromStart;
        private final Iterator<Integer> wrapped;

        /**
         * @param group the group value, which with the salt fixes the start id
         * @param walked the sorted ids this group steps through
         * @param mustAlsoBeIn the set a candidate must also belong to, or null when every walked id is a candidate
         */
        private GroupCursor(String group, NavigableSet<Integer> walked, Set<Integer> mustAlsoBeIn) {
            this.mustAlsoBeIn = mustAlsoBeIn;
            if (walked.isEmpty()) {
                this.fromStart = Collections.emptyIterator();
                this.wrapped = Collections.emptyIterator();
            } else {
                int start = startId(group, walked.first(), walked.last());
                this.fromStart = walked.tailSet(start, true).iterator();
                this.wrapped = walked.headSet(start, false).iterator();
            }
        }

        private Integer next(Set<Integer> alreadySampled) {
            Integer candidate = nextFrom(fromStart, alreadySampled);
            return candidate != null ? candidate : nextFrom(wrapped, alreadySampled);
        }

        private Integer nextFrom(Iterator<Integer> ids, Set<Integer> alreadySampled) {
            while (ids.hasNext()) {
                Integer id = ids.next();
                if ((mustAlsoBeIn == null || mustAlsoBeIn.contains(id)) && !alreadySampled.contains(id)) {
                    return id;
                }
            }
            return null;
        }
    }

    private int startId(String group, int lowestId, int highestId) {
        long idSpan = (long) highestId - lowestId + 1;
        return (int) (lowestId + Math.floorMod(saltedHash(group), idSpan));
    }

    private long saltedHash(String group) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(sampleProperties.getSalt().getBytes(StandardCharsets.UTF_8));
            sha256.update((byte) 0);
            sha256.update(group.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(sha256.digest()).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
