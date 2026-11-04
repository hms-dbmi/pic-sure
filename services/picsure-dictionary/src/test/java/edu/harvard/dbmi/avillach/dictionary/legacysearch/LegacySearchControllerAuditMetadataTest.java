package edu.harvard.dbmi.avillach.dictionary.legacysearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import edu.harvard.dbmi.avillach.dictionary.AuditAttributes;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.LegacySearchCriteria;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.LegacySearchQuery;
import edu.harvard.dbmi.avillach.dictionary.legacysearch.model.Results;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class LegacySearchControllerAuditMetadataTest {

    @Mock
    private LegacySearchService legacySearchService;

    private LegacySearchController subject;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        subject = new LegacySearchController(legacySearchService, new LegacySearchQueryMapper());
        request = new MockHttpServletRequest();
        ReflectionTestUtils.setField(subject, "httpRequest", request);
        when(legacySearchService.getSearchResults(any(), any())).thenReturn(new Results(List.of()));
    }

    @Test
    void recordsMapperProducedTsqueryInAuditMetadata() {
        subject.legacySearch(new LegacySearchQuery(new LegacySearchCriteria("tutorial-biolincc digitalis", 100)));

        assertEquals("tutorial:* & biolincc:* & digitalis:*", AuditAttributes.getMetadata(request).get("search_term"));
    }

    @Test
    void recordsEmptyAuditMetadataForAnEmptyRawTerm() {
        subject.legacySearch(new LegacySearchQuery(new LegacySearchCriteria("", 100)));

        assertEquals("", AuditAttributes.getMetadata(request).get("search_term"));
    }
}
