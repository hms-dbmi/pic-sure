package edu.harvard.dbmi.avillach.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.data.entity.Resource;
import edu.harvard.dbmi.avillach.data.repository.ResourceRepository;
import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.QueryRequest;
import edu.harvard.dbmi.avillach.domain.ResourceInfo;
import edu.harvard.dbmi.avillach.util.Utilities;
import edu.harvard.dbmi.avillach.util.exception.ApplicationException;
import edu.harvard.dbmi.avillach.util.exception.ProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.core.HttpHeaders;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class PicsureInfoService {

    private final Logger logger = LoggerFactory.getLogger(PicsureQueryService.class);

    private final static ObjectMapper mapper = new ObjectMapper();

    @Inject
    ResourceRepository resourceRepo;

    @Inject
    ResourceWebClient resourceWebClient;

    /**
     * Retrieve resource info for a specific resource.
     *
     * @param resourceId - Resource UUID
     * @param credentialsQueryRequest - Contains resource specific credentials map
     * @return a {@link edu.harvard.dbmi.avillach.domain.ResourceInfo ResourceInfo}
     */
    public ResourceInfo info(UUID resourceId, QueryRequest credentialsQueryRequest, HttpHeaders headers) {
        Resource resource = resourceRepo.getById(resourceId);
        if (resource == null) {
            throw new ProtocolException(ProtocolException.RESOURCE_NOT_FOUND + resourceId.toString());
        }
        if (resource.getResourceRSPath() == null) {
            throw new ApplicationException(ApplicationException.MISSING_RESOURCE_PATH);
        }
        if (credentialsQueryRequest == null) {
            credentialsQueryRequest = new GeneralQueryRequest();
        }
        if (credentialsQueryRequest.getResourceCredentials() == null) {
            credentialsQueryRequest.setResourceCredentials(new HashMap<String, String>());
        }

        logger.info(
            "path=/info/{resourceId}, resourceId={}, requestSource={}, credentialsQueryRequest={}", resourceId,
            Utilities.getRequestSourceFromHeader(headers), Utilities.convertQueryRequestToString(mapper, credentialsQueryRequest)
        );

        credentialsQueryRequest.getResourceCredentials().put(ResourceWebClient.BEARER_TOKEN_KEY, resource.getToken());
        return resourceWebClient.info(resource.getResourceRSPath(), credentialsQueryRequest);
    }

    /**
     * Retrieve a list of all available resources.
     *
     * @return List containing limited metadata about all available resources and ids.
     */
    public Map<UUID, String> resources(HttpHeaders headers) {
        logger.info("path=/info/resources, requestSource={}", Utilities.getRequestSourceFromHeader(headers));
        return resourceRepo.list().stream().filter(resource -> !resource.getHidden())
            .collect(Collectors.toMap(Resource::getUuid, Resource::getName));
    }
}
