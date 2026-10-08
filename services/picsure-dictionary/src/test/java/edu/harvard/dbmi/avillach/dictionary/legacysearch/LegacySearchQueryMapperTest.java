package edu.harvard.dbmi.avillach.dictionary.legacysearch;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.LegacySearchCriteria;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;

class LegacySearchQueryMapperTest {

    private final LegacySearchQueryMapper legacySearchQueryMapper = new LegacySearchQueryMapper();

    @Test
    void shouldParseSearchRequest() {
        LegacySearchCriteria criteria = new LegacySearchCriteria("age", 100);

        Filter filter = legacySearchQueryMapper.toFilter(criteria);
        Pageable pageable = legacySearchQueryMapper.toPageable(criteria);

        Assertions.assertEquals(new Filter(List.of(), "age:*", List.of()), filter);
        Assertions.assertEquals(0, pageable.getPageNumber());
        Assertions.assertEquals(100, pageable.getPageSize());
    }

    @Test
    void shouldHandlePunct() {
        Filter filter = legacySearchQueryMapper.toFilter(new LegacySearchCriteria("tutorial-biolincc_digitalis", 100));

        Assertions.assertEquals("tutorial:* & biolincc:* & digitalis:*", filter.search());
    }

    @Test
    void shouldHandleOR() {
        Filter filter = legacySearchQueryMapper.toFilter(new LegacySearchCriteria("sex|gender", 100));

        Assertions.assertEquals("sex:* | gender:*", filter.search());
    }

    @Test
    void shouldHandleORAndPunct() {
        Filter filter = legacySearchQueryMapper.toFilter(new LegacySearchCriteria("sex|gender age", 100));

        Assertions.assertEquals("sex:* | gender:* & age:*", filter.search());
    }

    @Test
    void shouldSearchWithNoTextWhenTheTermIsMissing() {
        Filter filter = legacySearchQueryMapper.toFilter(new LegacySearchCriteria(null, 100));

        Assertions.assertEquals("", filter.search());
    }
}
