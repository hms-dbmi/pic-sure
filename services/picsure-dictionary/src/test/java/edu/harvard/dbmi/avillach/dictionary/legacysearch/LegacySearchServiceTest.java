package edu.harvard.dbmi.avillach.dictionary.legacysearch;

import edu.harvard.dbmi.avillach.dictionary.concept.model.Concept;
import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * The tsquery translation that used to live in LegacySearchQueryMapper now lives in the service; these are the mapper's cases, asserted
 * through the search term the service actually hands the repository.
 */
class LegacySearchServiceTest {

    private final LegacySearchRepository repository = Mockito.mock(LegacySearchRepository.class);
    private final LegacySearchService subject = new LegacySearchService(repository);

    private String searchTermSentToRepository(String rawSearchTerm) {
        Mockito.when(repository.getLegacySearchResults(Mockito.any(), Mockito.any())).thenReturn(List.of());
        subject.getSearchResults(rawSearchTerm, PageRequest.of(0, 100));

        ArgumentCaptor<Filter> filter = ArgumentCaptor.forClass(Filter.class);
        Mockito.verify(repository).getLegacySearchResults(filter.capture(), Mockito.any(Pageable.class));
        return filter.getValue().search();
    }

    @Test
    void shouldWildcardASingleTerm() {
        Assertions.assertEquals("age:*", searchTermSentToRepository("age"));
    }

    @Test
    void shouldSplitOnPunctuation() {
        Assertions.assertEquals("tutorial:* & biolincc:* & digitalis:*", searchTermSentToRepository("tutorial-biolincc_digitalis"));
    }

    @Test
    void shouldTreatPipeAsOr() {
        Assertions.assertEquals("sex:* | gender:*", searchTermSentToRepository("sex|gender"));
    }

    @Test
    void shouldTreatPipeAsOrAndWhitespaceAsAnd() {
        Assertions.assertEquals("sex:* | gender:* & age:*", searchTermSentToRepository("sex|gender age"));
    }

    @Test
    void shouldSurviveAnAbsentSearchTerm() {
        Assertions.assertEquals("", searchTermSentToRepository(null));
    }

    @Test
    void shouldStripTsQueryOperatorsACallerTriesToInject() {
        // ! and : are tsquery operators; splitting on punctuation means only our own operators reach postgres.
        Assertions.assertEquals("age:* & foo:*", searchTermSentToRepository("age & !foo:*"));
    }

    @Test
    void shouldPassPaginationThrough() {
        Mockito.when(repository.getLegacySearchResults(Mockito.any(), Mockito.any())).thenReturn(List.<Concept>of());

        subject.getSearchResults("age", PageRequest.of(2, 25));

        Mockito.verify(repository).getLegacySearchResults(Mockito.any(), Mockito.eq(PageRequest.of(2, 25)));
    }
}
