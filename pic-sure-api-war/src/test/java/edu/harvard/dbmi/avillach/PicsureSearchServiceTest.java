package edu.harvard.dbmi.avillach;

import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.service.AuditContext;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.dbmi.avillach.service.PicsureSearchService;
import edu.harvard.dbmi.avillach.service.ResourceWebClient;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.UUID;

import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;

import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MockitoSettings(strictness = Strictness.WARN)
@ExtendWith(MockitoExtension.class)
public class PicsureSearchServiceTest extends BaseServiceTest {

    private UUID resourceId = UUID.randomUUID();

    @InjectMocks
    private PicsureSearchService searchService = new PicsureSearchService();

    @Mock
    private Resource mockResource = mock(Resource.class);

    @Mock
    private ResourceRepository resourceRepo = mock(ResourceRepository.class);

    @Mock
    private ResourceWebClient webClient = mock(ResourceWebClient.class);

    @Mock
    private AuditContext auditContext = mock(AuditContext.class);

    @BeforeEach
    public void setUp() {
        SearchResults results = new SearchResults();
        when(resourceRepo.getById(resourceId)).thenReturn(mockResource);
        when(resourceRepo.getById(not(ArgumentMatchers.same(resourceId)))).thenReturn(null);
        when(webClient.search(any(), any())).thenReturn(results);
    }

    @Test
    public void testSearch() {
        GeneralQueryRequest searchQueryRequest = new GeneralQueryRequest();
        Map<String, String> clientCredentials = new HashMap<String, String>();
        clientCredentials.put("bearer key", "bearer token");
        searchQueryRequest.setResourceCredentials(clientCredentials);
        searchQueryRequest.setQuery("blood");

        try {
            SearchResults results = searchService.search(resourceId, searchQueryRequest, null);
            fail("Missing request data should throw an error");
        } catch (ApplicationException e) {
            assertNotNull(e.getContent());
            assertEquals(
                ApplicationException.MISSING_RESOURCE_PATH, e.getContent().toString(),
                "Error message should say '" + ApplicationException.MISSING_RESOURCE_PATH + "'"
            );
        }

        when(mockResource.getResourceRSPath()).thenReturn("resourceRsPath");

        // Missing requestdata should throw an error
        try {
            SearchResults results = searchService.search(resourceId, null, null);
            fail("Missing request data should throw an error");
        } catch (ProtocolException e) {
            assertNotNull(e.getContent());
            assertEquals(
                ProtocolException.MISSING_DATA, e.getContent().toString(),
                "Error message should say '" + ProtocolException.MISSING_DATA + "'"
            );
        }

        // Missing resourceId should error
        try {
            SearchResults results = searchService.search(null, searchQueryRequest, null);
            fail("Missing resourceId should throw an error");
        } catch (ProtocolException e) {
            assertNotNull(e.getContent());
            assertEquals(
                ProtocolException.MISSING_RESOURCE_ID, e.getContent().toString(),
                "Error message should say '" + ProtocolException.MISSING_RESOURCE_ID + "'"
            );

        }

        // Nonexistent resourceId should error
        try {
            SearchResults results = searchService.search(UUID.randomUUID(), searchQueryRequest, null);
            fail("Nonexistent resourceId should throw an error");
        } catch (ProtocolException e) {
            assertNotNull(e.getContent());
            assertTrue(
                e.getContent().toString().contains(ProtocolException.RESOURCE_NOT_FOUND),
                "Error message should say '" + ProtocolException.RESOURCE_NOT_FOUND + "'"
            );
        }

        // This should work
        SearchResults results = searchService.search(resourceId, searchQueryRequest, null);
        assertNotNull(results, "SearchResults should not be null");

        // There should also be no problem if the resourceCredentials are null
        searchQueryRequest.setResourceCredentials(null);
        results = searchService.search(resourceId, searchQueryRequest, null);
        assertNotNull(results, "SearchResults should not be null");
    }

    @Test
    public void testSearchSetsAuditContext() {
        when(mockResource.getResourceRSPath()).thenReturn("resourceRsPath");
        when(mockResource.getName()).thenReturn("test-resource");

        GeneralQueryRequest searchQueryRequest = new GeneralQueryRequest();
        searchQueryRequest.setResourceCredentials(new HashMap<>());
        searchQueryRequest.setQuery("blood");

        searchService.search(resourceId, searchQueryRequest, null);

        verify(auditContext).put("resource_id", resourceId.toString());
        verify(auditContext).put("resource_name", "test-resource");
        verify(auditContext).put("search_term", "blood");
    }
}
