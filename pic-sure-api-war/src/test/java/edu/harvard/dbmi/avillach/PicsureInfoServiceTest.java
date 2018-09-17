package edu.harvard.dbmi.avillach;

import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
        when(resourceRepo.getById(resourceId)).thenReturn(mockResource);
        when(resourceRepo.getById(not(ArgumentMatchers.same(resourceId)))).thenReturn(null);
        when(webClient.info(any(), any())).thenReturn(results);
    }

    @Test
    public void testInfoEndpoints() {
        Map<String, String> clientCredentials = new HashMap<String, String>();

        //Should fail with a nonexistent id
        try {
            ResourceInfo info = infoService.info(UUID.randomUUID(), clientCredentials);
            fail();
        } catch (ProtocolException e){
            assertNotNull(e.getContent());
            assertTrue("Error message should say '" + ProtocolException.RESOURCE_NOT_FOUND + "'", e.getContent().toString().contains(ProtocolException.RESOURCE_NOT_FOUND));        }

        //Should fail without the url in the resource
        try {
            ResourceInfo info = infoService.info(resourceId, clientCredentials);
            fail();
        } catch (ApplicationException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + ApplicationException.MISSING_RESOURCE_PATH + "'", ApplicationException.MISSING_RESOURCE_PATH, e.getContent().toString());
        }
        when(mockResource.getResourceRSPath()).thenReturn("resourceRsPath");

        //Should fail without the url in the resource
        try {
            ResourceInfo info = infoService.info(resourceId, clientCredentials);
            fail();
        } catch (ApplicationException e){
            assertNotNull(e.getContent());
            assertEquals("Error message should say '" + ApplicationException.MISSING_TARGET_URL + "'", ApplicationException.MISSING_TARGET_URL, e.getContent().toString());
        }

        when(mockResource.getTargetURL()).thenReturn("testUrl");

        ResourceInfo responseInfo = infoService.info(resourceId, clientCredentials);
        assertNotNull("Resource response should not be null", responseInfo);

        //Should also work without clientCredentials
        responseInfo = infoService.info(resourceId, null);
        assertNotNull("Resource response should not be null", responseInfo);
    }
}
