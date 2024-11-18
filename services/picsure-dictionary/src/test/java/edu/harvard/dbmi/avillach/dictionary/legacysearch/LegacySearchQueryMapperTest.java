package edu.harvard.dbmi.avillach.dictionary.legacysearch;

import edu.harvard.dbmi.avillach.dictionary.filter.Filter;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.LegacySearchQuery;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;

@SpringBootTest
@ActiveProfiles("test")
class LegacySearchQueryMapperTest {

    @Autowired
    LegacySearchQueryMapper legacySearchQueryMapper;

    @Test
    void shouldParseSearchRequest() throws IOException {
        String jsonString = """
            {"query":{"searchTerm":"age","includedTags":[],"excludedTags":[],"returnTags":"true","offset":0,"limit":100}}
            """;

        LegacySearchQuery legacySearchQuery = legacySearchQueryMapper.mapFromJson(jsonString);
        Filter filter = legacySearchQuery.filter();
        Pageable pageable = legacySearchQuery.pageable();

        Assertions.assertEquals("age", filter.search());
        Assertions.assertEquals(100, pageable.getPageSize());
    }

    @Test
    void shouldReplaceUnderscore() throws IOException {
        String jsonString =
            """
                {"query":{"searchTerm":"tutorial-biolincc_digitalis","includedTags":[],"excludedTags":[],"returnTags":"true","offset":0,"limit":100}}
                """;

        LegacySearchQuery legacySearchQuery = legacySearchQueryMapper.mapFromJson(jsonString);
        Filter filter = legacySearchQuery.filter();
        Pageable pageable = legacySearchQuery.pageable();

        Assertions.assertEquals("tutorial-biolincc/digitalis", filter.search());
        Assertions.assertEquals(100, pageable.getPageSize());
    }

}
