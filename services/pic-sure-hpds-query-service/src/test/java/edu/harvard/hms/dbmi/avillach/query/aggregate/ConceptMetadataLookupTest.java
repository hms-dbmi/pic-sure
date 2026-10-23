package edu.harvard.hms.dbmi.avillach.query.aggregate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.SearchResults;

class ConceptMetadataLookupTest {

    private static final String AGE = "\\age\\";

    private ConceptMetadataLookup resolverWith(Map<String, Object> phenotypes) {
        AggregateBackendClient backend = mock(AggregateBackendClient.class);
        when(backend.search(any())).thenAnswer(inv -> {
            String concept = (String) ((QueryRequest) inv.getArgument(0)).getQuery();
            return new SearchResults().setResults(
                Map.of(
                    "phenotypes",
                    phenotypes.entrySet().stream().filter(e -> e.getKey().contains(concept))
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
                )
            );
        });
        return new ConceptMetadataLookup(backend);
    }

    @Test
    void usesTheConceptsOverallRangeFromSearch() {
        ConceptMetadataLookup resolver = resolverWith(Map.of(AGE, Map.of("name", AGE, "min", 0.5, "max", 99.0, "categorical", false)));
        assertThat(resolver.overallRanges(Set.of(AGE))).containsExactly(Map.entry(AGE, new ChartRange(0.5, 99)));
    }

    @Test
    void matchesTheExactConceptAmongSubstringHits() {
        ConceptMetadataLookup resolver =
            resolverWith(Map.of(AGE, Map.of("min", 0, "max", 120), "\\age\\at_visit\\", Map.of("min", 1, "max", 2)));
        assertThat(resolver.overallRanges(Set.of(AGE))).containsExactly(Map.entry(AGE, new ChartRange(0, 120)));
    }

    @Test
    void leavesOutConceptsWithNoKnownRange() {
        ConceptMetadataLookup resolver = resolverWith(Map.of(AGE, Map.of("min", 0, "max", 120), "\\sex\\", Map.of("categorical", true)));
        assertThat(resolver.overallRanges(Set.of(AGE, "\\sex\\", "\\missing\\"))).containsOnlyKeys(AGE);
    }

    @Test
    void returnsEveryCategoryValueOfAConcept() {
        ConceptMetadataLookup lookup =
            resolverWith(Map.of("\\sex\\", Map.of("categorical", true, "categoryValues", java.util.List.of("Female", "Male", "Unknown"))));
        assertThat(lookup.categoryValues(Set.of("\\sex\\"))).containsEntry("\\sex\\", Set.of("Female", "Male", "Unknown"));
    }

    @Test
    void leavesOutConceptsWithNoCategoryValues() {
        ConceptMetadataLookup lookup = resolverWith(Map.of(AGE, Map.of("min", 0, "max", 120)));
        assertThat(lookup.categoryValues(Set.of(AGE, "\\missing\\"))).isEmpty();
    }
}
