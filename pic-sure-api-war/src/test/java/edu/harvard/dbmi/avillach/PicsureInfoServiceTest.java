package edu.harvard.dbmi.avillach;

import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.ResourceInfo;
import edu.harvard.dbmi.avillach.service.PicsureInfoService;
import edu.harvard.dbmi.avillach.service.ResourceWebClient;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.*;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.*;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class PicsureInfoServiceTest extends BaseServiceTest {

    private UUID resourceId = UUID.randomUUID();

    @InjectMocks
    private PicsureInfoService infoService = new PicsureInfoService();

    @Mock
    private Resource mockResource = mock(Resource.class);

    @Mock
    private ResourceRepository resourceRepo = mock(ResourceRepository.class);

    @Mock
    private ResourceWebClient webClient = mock(ResourceWebClient.class);

    @Before
    public void setUp() {
        ResourceInfo results = new ResourceInfo();
        Resource testResource = new Resource().setName("A Mock Resource");
        testResource.setUuid(resourceId);
        List<Resource> resourceListing = List.of(mockResource);

        when(mockResource.getName()).thenReturn("A Mock Resource");
        when(mockResource.getUuid()).thenReturn(resourceId);

        when(resourceRepo.getById(resourceId)).thenReturn(mockResource);
        when(resourceRepo.getById(not(ArgumentMatchers.same(resourceId)))).thenReturn(null);
        when(webClient.info(any(), any())).thenReturn(results);
        when(resourceRepo.list()).thenReturn(resourceListing);
    }

    @Test
    public void testInfoEndpoints() {
        QueryRequest infoRequest = new QueryRequest();
        Map<String, String> clientCredentials = new HashMap<String, String>();
        infoRequest.setResourceCredentials(clientCredentials);

        //Should fail with a nonexistent id
        try {
            ResourceInfo info = infoService.info(UUID.randomUUID(), infoRequest, null);
            fail();
        } catch (ProtocolException e){
            assertNotNull(e.getContent());
            assertTrue("Error message should say '" + ProtocolException.RESOURCE_NOT_FOUND + "'", e.getContent().toString().contains(ProtocolException.RESOURCE_NOT_FOUND));        }

        //Should fail without the url in the resource
        try {
            ResourceInfo info = infoService.info(resourceId, infoRequest, null);
            fail();
        } catch (ApplicationException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + ApplicationException.MISSING_RESOURCE_PATH + "'", ApplicationException.MISSING_RESOURCE_PATH, e.getContent().toString());
        }
        when(mockResource.getResourceRSPath()).thenReturn("resourceRsPath");

        ResourceInfo responseInfo = infoService.info(resourceId, infoRequest, null);
        assertNotNull("Resource response should not be null", responseInfo);

        //Should also work without clientCredentials
        responseInfo = infoService.info(resourceId, null, null);
        assertNotNull("Resource response should not be null", responseInfo);
    }

    @Test
    public void testResourcesEndpoint() {
        //Should give a UUID list of all resources
        Map<UUID, String> resourceList = infoService.resources(null);
        assertNotNull("Resource listing should not be null", resourceList);
        assertEquals("Resource listing should only have 1 entry", 1, resourceList.size());
        assertSame("Resource listing should be UUID of our mocked resource", resourceId, resourceList.keySet().iterator().next());
    }
}
