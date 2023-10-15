package edu.harvard.dbmi.avillach;

import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.domain.SearchResults;
import edu.harvard.dbmi.avillach.service.PicsureSearchService;
import edu.harvard.dbmi.avillach.service.ResourceWebClient;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import org.junit.Before;
import org.junit.Test;

import java.util.UUID;

import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.HashMap;
import java.util.Map;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
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

    @Before
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
        } catch (ApplicationException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + ApplicationException.MISSING_RESOURCE_PATH + "'", ApplicationException.MISSING_RESOURCE_PATH, e.getContent().toString());
        }

        when(mockResource.getResourceRSPath()).thenReturn("resourceRsPath");

        //Missing requestdata should throw an error
        try {
            SearchResults results = searchService.search(resourceId, null, null);
            fail("Missing request data should throw an error");
        } catch (ProtocolException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + ProtocolException.MISSING_DATA + "'", ProtocolException.MISSING_DATA, e.getContent().toString());
        }

        //Missing resourceId should error
        try {
            SearchResults results = searchService.search(null, searchQueryRequest, null);
            fail("Missing resourceId should throw an error");
        } catch (ProtocolException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + ProtocolException.MISSING_RESOURCE_ID + "'", ProtocolException.MISSING_RESOURCE_ID, e.getContent().toString());

        }

        //Nonexistent resourceId should error
        try {
            SearchResults results = searchService.search(UUID.randomUUID(), searchQueryRequest, null);
            fail("Nonexistent resourceId should throw an error");
        } catch (ProtocolException e){
            assertNotNull(e.getContent());
            assertTrue("Error message should say '" + ProtocolException.RESOURCE_NOT_FOUND + "'", e.getContent().toString().contains(ProtocolException.RESOURCE_NOT_FOUND));
        }

        //This should work
        SearchResults results = searchService.search(resourceId, searchQueryRequest, null);
        assertNotNull("SearchResults should not be null", results);

        //There should also be no problem if the resourceCredentials are null
        searchQueryRequest.setResourceCredentials(null);
        results = searchService.search(resourceId, searchQueryRequest, null);
        assertNotNull("SearchResults should not be null", results);
    }
}
